package uk.dsx.ats;

import lombok.Value;
import org.apache.logging.log4j.Logger;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.marketdata.MarketDataService;
import uk.dsx.ats.data.AlgorithmArgs;
import uk.dsx.ats.data.PriceProperties;
import uk.dsx.ats.utils.DSXUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static uk.dsx.ats.AtsMain.*;
import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

@Value
class Algorithm {

    private static final BigDecimal LOW_VOLUME = new BigDecimal("0.1");

    AlgorithmArgs args;
    Logger logInfo;
    Logger logAudit;
    Date date;

    Algorithm(AlgorithmArgs args, Logger logInfo, Logger logAudit, Date date) {
        this.args = args;
        this.logInfo = logInfo;
        this.logAudit = logAudit;
        this.date = date;
    }

    private BigDecimal getBidOrderHighestPriceDSX() throws IOException {
        List<LimitOrder> bids = getBidOrders(args.getDsxExchange(), DSX_CURRENCY_PAIR);
        LimitOrder highestBid;
        highestBid = bids.get(0);
        return highestBid.getLimitPrice();
    }

    private List<LimitOrder> getBidOrders(Exchange exchange, CurrencyPair currencyPair) throws IOException {
        MarketDataService marketDataService = exchange.getMarketDataService();
        List<LimitOrder> orders = marketDataService.getOrderBook(currencyPair, PRICE_PROPERTIES.getDsxAccountType()).getBids();
        while (orders.isEmpty()) {
            orders = marketDataService.getOrderBook(currencyPair, PRICE_PROPERTIES.getDsxAccountType()).getBids();
            logInfo("DSX orderbook is empty, waiting for orderbook appearance");
            DSXUtils.sleep("Request to get orderbook interrupted");
        }

        return orders;
    }

    private int getOrderIndex(BigDecimal price, List<LimitOrder> orders) {

        return IntStream.range(0, orders.size())
                .filter(i -> orders.get(i).getLimitPrice().compareTo(price) == 0)
                .findFirst().orElse(-1);
    }

    private BigDecimal getPriceAfterOrderDSX(BigDecimal price) throws IOException {

        List<LimitOrder> orders = getBidOrders(args.getDsxExchange(), DSX_CURRENCY_PAIR);

        BigDecimal priceAfterUserOrder = null;

        int orderIndex = getOrderIndex(price, orders);

        // take order price after user's order
        if (orders.size() - 1 > orderIndex)
            priceAfterUserOrder = orders.get(orderIndex + 1).getLimitPrice();

        return priceAfterUserOrder;
    }

    private BigDecimal getVolumeBeforeOrderDSX(BigDecimal price) throws IOException {
        BigDecimal volume = BigDecimal.ZERO;

        List<LimitOrder> orders = getBidOrders(args.getDsxExchange(), DSX_CURRENCY_PAIR);
        for (LimitOrder order : orders) {
            if ((order.getLimitPrice().compareTo(price) > 0) && (order.getRemainingAmount() != null)) {
                volume = volume.add(order.getRemainingAmount());
            }
        }
        return volume;
    }

    void cancelAllOrders(DSXTradeService tradeService) throws Exception {
        // set limit order return value for placing new order
        args.setOrderId(0L);
        args.setLimitOrderReturnValue(null);
        // setting average price to null for updating dsx price, when cancelling order
        args.setAveragePrice(null);

        DSXUtils.unlimitedRepeatableRequest("cancelAllOrders", tradeService::cancelAllOrders);
        logInfo("Cancelled all active account orders");
    }

    private void printOrderStatus(int orderStatus) {
        switch (orderStatus) {
            case 0:
                logInfo("Actual order status: Active");
                break;
            case 1:
                logInfo("Actual order status: Filled");
                break;
            case 2:
                logInfo("Actual order status: Killed");
                break;
            default:
                logInfo("Actual order status: {}", orderStatus);
                break;
        }
    }

    private boolean checkPriceBeforeOrder(BigDecimal orderPrice, BigDecimal priceBeforeOrder, BigDecimal stepToMove) {
        return priceBeforeOrder != null && orderPrice.subtract(priceBeforeOrder).abs().compareTo(stepToMove) > 0;
    }

    private boolean checkPriceAfterOrder(BigDecimal priceAfterOrder, BigDecimal rate, BigDecimal sensitivity) {
        return priceAfterOrder != null && rate.subtract(priceAfterOrder).compareTo(sensitivity) > 0;
    }

    private boolean checkVolumeBeforeOrder(BigDecimal volumeBeforeOrder, BigDecimal volumeToMove) {
        return volumeBeforeOrder.compareTo(volumeToMove) >= 0;
    }

    boolean executeAlgorithm() throws Exception {
        PriceProperties priceConstants = args.getPriceProperties();

        Balance balance = DSXUtils.unlimitedRepeatableRequest("getFunds", () ->
                getFunds(args.getDsxExchange(), DSX_CURRENCY_PAIR.counter));

        logInfo("Account funds: {}", balance);
        //waiting for our price to be better than average price on supported exchanges
        awaitGoodPrice();

        //calculating data for placing order on our exchange
        BigDecimal dsxPriceWithAddition = args.getDsxPrice().add(priceConstants.getPriceAddition());

        BigDecimal volume = DSXUtils.unlimitedRepeatableRequest("getFunds", () ->
                getFunds(args.getDsxExchange(), DSX_CURRENCY_PAIR.counter).getAvailable().divide(dsxPriceWithAddition, priceConstants.getVolumeScale(),
                        RoundingMode.DOWN));

        // condition for not printing volume, when this is redundant
        if (volume.compareTo(LOW_VOLUME) > 0) {

            cancelAllOrders((DSXTradeService) args.getDsxTradeServiceRaw());
            volume = DSXUtils.unlimitedRepeatableRequest("getFunds", () ->
                    getFunds(args.getDsxExchange(), DSX_CURRENCY_PAIR.counter).getAvailable().divide(dsxPriceWithAddition, priceConstants.getVolumeScale(),
                            RoundingMode.DOWN));
            logInfo("Cancelled all previous orders in case there was placed order");
            logInfo("Buying volume: {}", volume);
        }

        try {
            //check if we have enough money to place order
            if (volume.compareTo(LOW_VOLUME) < 0) {

                //we don't have enough money to place order
                //if we have previously placed order we should check it's status
                if (args.getOrderId() != 0L) {
                    // if order status is filled - algorithm executed successfully
                    int orderStatus = DSXUtils.unlimitedRepeatableRequest("getOrderStatus", () ->
                            args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()).getStatus());

                    if (orderStatus == 1) {
                        logInfo("Order status is filled");
                        return true;
                    }
                } else {
                    //if we don't have any order it means there is not enough money on our account. Algorithm stopping.
                    logError("Couldn't place order. Not enough money.");
                    return true;
                }
            }
            if (args.getLimitOrderReturnValue() == null && dsxPriceWithAddition.compareTo(args.getPriceProperties().getMaxPrice()) < 0) {
                //place new order
                BigDecimal finalVolume = volume;
                String limitOrderReturnValue = DSXUtils.unlimitedRepeatableRequest("placeLimitOrder", () ->
                        args.getTradeService().placeLimitOrder(new LimitOrder(Order.OrderType.BID, finalVolume, DSX_CURRENCY_PAIR,
                                "", date, dsxPriceWithAddition)));
                args.setOrderPrice(dsxPriceWithAddition);
                logInfo("Order with id {} was placed", limitOrderReturnValue);
                args.setLimitOrderReturnValue(limitOrderReturnValue);
            }

            args.setOrderId(Long.parseLong(args.getLimitOrderReturnValue()));

            //sleep some time before checking order status
            TimeUnit.SECONDS.sleep(priceConstants.getWaitingTimeForOrderCheck());

            //get actual order status
            if (args.getOrderId() != 0L) {
                DSXOrderStatusResult result = DSXUtils.unlimitedRepeatableRequest("getOrderStatus", () ->
                        args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()));
                printOrderStatus(result.getStatus());

                BigDecimal priceBeforeOrder = DSXUtils.unlimitedRepeatableRequest("getBidOrderHighestPriceDSX",
                        this::getBidOrderHighestPriceDSX);

                // Order status == Filled - algorithm executed correctly
                if (result.getStatus() == 1) {
                    logInfo("Order was filled");
                    return true;
                }

                // if order status not filled - check that order is actual (top bid or so and price is good).
                BigDecimal volumeBeforeOrder = DSXUtils.unlimitedRepeatableRequest("getVolumeBeforeVolume", () ->
                        getVolumeBeforeOrderDSX(result.getRate()));

                BigDecimal priceAfterOrder = DSXUtils.unlimitedRepeatableRequest("getPriceAfterOrderDSX", () ->
                        getPriceAfterOrderDSX(result.getRate()));

                // good means that difference between user's order price and order's price after is less than sensitivity
                boolean isPriceAfterOrderGood = checkPriceAfterOrder(
                        priceAfterOrder,
                        result.getRate(),
                        priceConstants.getSensitivity());

                // good means that difference between user's order price and order's price before is less than step to move
                boolean isPriceBeforeOrderGood = checkPriceBeforeOrder(
                        result.getRate(),
                        priceBeforeOrder,
                        priceConstants.getStepToMove());

                boolean isVolumeGood = checkVolumeBeforeOrder(volumeBeforeOrder, priceConstants.getVolumeToMove());

                if (isPriceBeforeOrderGood || isVolumeGood || isPriceAfterOrderGood) {
                    //if price is good and order needs to be replaced (with better price). check order status again
                    int orderStatus = DSXUtils.unlimitedRepeatableRequest("getOrderStatus", () ->
                            args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()).getStatus());

                    printOrderStatus(orderStatus);
                    // if order status is not filled or killed, then cancel order so we can place another order
                    if (orderStatus != 1 && orderStatus != 2) {
                        DSXUtils.unlimitedRepeatableRequest("cancelOrder", () ->
                                args.getTradeService().cancelOrder(args.getLimitOrderReturnValue()));
                        logInfo("Cancelling order, because better price exists");
                        args.setOrderId(0L);
                        args.setAveragePrice(null);
                    }
                    args.setLimitOrderReturnValue(null);
                }
            }
        } catch (ExchangeException e) {
            logErrorWithException("Exchange exception: {}", e);
        }
        return false;
    }


    private void awaitGoodPrice() throws Exception {
        PriceProperties priceProperties = args.getPriceProperties();

        while (args.getAveragePrice() == null || isDSXPriceBad(priceProperties)) {
            //get new average price from other exchanges
            args.setAveragePrice(AVERAGE_PRICE.getAveragePrice(priceProperties.getTimestampForPriceUpdate(),
                    priceProperties.getPriceScale()));

            //get DSX price
            args.setDsxPrice(DSXUtils.unlimitedRepeatableRequest("getBidOrderHighestPriceDSX",
                    this::getBidOrderHighestPriceDSX));


            if (args.getAveragePrice() != null) {
                if (!DSX_CURRENCY_PAIR.equals(EXCHANGES_CURRENCY_PAIR))
                    logInfo("Average price: {} ({}), dsxPrice: {} ({}), fiat exchange rate: {}",
                            args.getAveragePrice().divide(args.getFxRate(), priceProperties.getPriceScale(), RoundingMode.DOWN),
                            DSX_CURRENCY_PAIR, args.getDsxPrice(), DSX_CURRENCY_PAIR, args.getFxRate());
                else
                    logInfo("Average price: {} ({}), dsxPrice: {} ({})",
                            args.getAveragePrice(), EXCHANGES_CURRENCY_PAIR, args.getDsxPrice(), DSX_CURRENCY_PAIR);

                //if DSX price is bad
                if (isDSXPriceBad(priceProperties)) {
                    //if we have previously placed order - we should kill it or it can be filled by bad price.
                    if (args.getOrderId() != 0L) {
                        try {
                            cancelAllOrders((DSXTradeService) args.getDsxTradeServiceRaw());
                            logInfo("Previous order was cancelled, because it had bad price");
                        } catch (ExchangeException e) {
                            logError("Order was already filled or killed.");
                            return;
                        }
                        args.setOrderId(0L);
                    }
                    logInfo("Cannot execute order. Waiting for price changing...");
                    //if DSX price is bad - waiting
                    TimeUnit.MILLISECONDS.sleep(priceProperties.getAveragePriceUpdateTime());
                }
            } else {
                // if we cannot get price from any exchange than cancel order, bcs average price can become better
                cancelAllOrders((DSXTradeService) args.getDsxTradeServiceRaw());
                logInfo("Waiting for connection to exchanges");
                DSXUtils.sleep("Sleep was interrupted");
            }
        }
    }

    private boolean isDSXPriceBad(PriceProperties priceProperties) {
        args.setAveragePrice(AVERAGE_PRICE.getAveragePrice(priceProperties.getTimestampForPriceUpdate(),
                priceProperties.getPriceScale()));
        BigDecimal averagePrice = args.getAveragePrice();
        BigDecimal dsxPrice = args.getDsxPrice();

        BigDecimal fxMultiplier = BigDecimal.ONE;
        // if DSX currency is not equal to currency pair on other exchanges, get currency exchange rate and multiply it by currency exchange fee
        if (!DSX_CURRENCY_PAIR.equals(EXCHANGES_CURRENCY_PAIR)) {
            if (args.getFxRate() == null) {
                logInfo(String.format("Unable to get exchange rate for currencies: %s/%s", DSX_CURRENCY_PAIR.counter, EXCHANGES_CURRENCY_PAIR.counter));
                return true;
            }
            fxMultiplier = args.getFxRate().multiply(priceProperties.getFxPercentage());
        }

        return averagePrice == null || averagePrice.compareTo(dsxPrice.multiply(priceProperties.getPricePercentage()).multiply(fxMultiplier)) < 0;
    }
}
