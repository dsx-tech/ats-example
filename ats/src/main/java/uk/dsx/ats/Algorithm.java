package uk.dsx.ats;

import lombok.Value;
import org.apache.logging.log4j.Logger;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.marketdata.MarketDataService;
import si.mazi.rescu.HttpStatusIOException;
import uk.dsx.ats.data.AlgorithmArgs;
import uk.dsx.ats.data.PriceProperties;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static uk.dsx.ats.AtsMain.*;
import static uk.dsx.ats.utils.DSXUtils.PRICE_PROPERTIES;
import static uk.dsx.ats.utils.DSXUtils.logInfo;
import static uk.dsx.ats.utils.DSXUtils.logError;
import static uk.dsx.ats.utils.DSXUtils.logErrorWithException;

/**
 * @author Mikhail Wall
 */

@Value
public class Algorithm {

    private static final int REQUEST_TO_DSX_TIMEOUT_SECONDS = 10;
    private static final BigDecimal LOW_VOLUME = new BigDecimal("0.1");

    AlgorithmArgs args;
    Logger logInfo;
    Logger logAudit;
    Date date;

    public Algorithm(AlgorithmArgs args, Logger logInfo, Logger logAudit, Date date) {
        this.args = args;
        this.logInfo = logInfo;
        this.logAudit = logAudit;
        this.date = date;
    }

    @FunctionalInterface
    public interface ConnectorRequest<T> {
        T get() throws Exception;
    }

    private void sleep(String interruptedMessage) {
        try {
            TimeUnit.SECONDS.sleep(REQUEST_TO_DSX_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            if (interruptedMessage != null)
                logInfo.warn(interruptedMessage);
        }
    }

    private <T> T unlimitedRepeatableRequest(String methodName, ConnectorRequest<T> requestObject) throws Exception {
        while (!Thread.interrupted()) {
            try {
                return requestObject.get();
            } catch (ConnectException | UnknownHostException | SocketTimeoutException | HttpStatusIOException e) {
                logError("Connection to dsx.uk disappeared, waiting 1 sec to try again", e.getMessage());
                sleep(String.format("%s interrupted", methodName));
            } catch (Exception e) {
                if (e.getMessage().contains("418")) {
                    logErrorWithException("Cannot connect to dsx.uk, waiting 1 sec to try again", e);
                    sleep(String.format("%s interrupted", methodName));
                } else
                    throw e;
            }
        }
        throw new InterruptedException(String.format("%s interrupted", methodName));
    }

    private BigDecimal getBidOrderHighestPriceDSX(Exchange exchange) throws IOException {
        MarketDataService marketDataService = exchange.getMarketDataService();
        List<LimitOrder> bids = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
        LimitOrder highestBid;
        while (bids.isEmpty()) {
            bids = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
            logInfo("DSX orderbook is empty, waiting for orderbook appearance");
            sleep("Request to get orderbook interrupted");
        }
        highestBid = bids.get(0);
        return highestBid.getLimitPrice();
    }

    private BigDecimal getPriceBeforeThanOrder(Exchange exchange, BigDecimal rate) throws IOException {
        MarketDataService marketDataService = exchange.getMarketDataService();
        List<LimitOrder> orders = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
        while (orders.isEmpty()) {
            orders = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
            logInfo("DSX orderbook is empty, waiting for orderbook appearance");
            sleep("Request to get orderbook interrupted");
        }

        int orderIndex = getOrderIndex(rate, orders);
        BigDecimal priceBeforeOrder = null;
        if (orders.size() - 1 > orderIndex) {
            priceBeforeOrder = orders.get(orderIndex - 1).getLimitPrice();
        }

        return priceBeforeOrder;
//        MarketDataService marketDataService = exchange.getMarketDataService();
//        List<LimitOrder> bids = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
//        BigDecimal priceBeforeUsersOrder = null;
//        while (bids.isEmpty()) {
//            bids = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
//            logInfo("DSX orderbook is empty, waiting for orderbook appearance");
//            sleep("Request to get orderbook interrupted");
//        }
//
//        bid = bids.stream()
//                .filter(bd -> bd.getLimitPrice().compareTo(rate) == 0).findFirst().orElse(null);
//
//        int orderIndex = IntStream.range(0, bids.size()).
//        if (bid != null && bids.size() > 1) {
//            return bids.get(bids.indexOf(bid)-1).getLimitPrice();
//        }
//        return null;
    }

    private int getOrderIndex(BigDecimal price, List<LimitOrder> orders) throws IOException {

        return IntStream.range(0, orders.size())
                .filter(i -> orders.get(i).getLimitPrice().compareTo(price) == 0)
                .findFirst().orElse(-1);
    }

    private BigDecimal getPriceAfterOrder(Exchange exchange, BigDecimal price) throws IOException {
        MarketDataService marketDataService = exchange.getMarketDataService();
        List<LimitOrder> orders = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
        BigDecimal priceAfterUserOrder = null;

        // index in order book bids for user's order
        int indexOrder = IntStream.range(0, orders.size())
                .filter(i -> orders.get(i).getLimitPrice().compareTo(price) == 0)
                .findFirst().orElse(-1);

        // take order price after user's order
        if (orders.size() - 1 > indexOrder)
            priceAfterUserOrder = orders.get(indexOrder + 1).getLimitPrice();

        return priceAfterUserOrder;
    }

    private BigDecimal getVolumeBeforeOrder(Exchange exchange, BigDecimal price) throws IOException {
        MarketDataService marketDataService = exchange.getMarketDataService();
        BigDecimal volume = BigDecimal.ZERO;

        List<LimitOrder> orders = marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids();
        for (LimitOrder order : orders) {
            if (order.getLimitPrice().compareTo(price) > 0) {
                volume = volume.add(order.getTradableAmount());
            }
        }
        return volume;
    }

    public void cancelAllOrders(DSXTradeService tradeService) throws Exception {

        // set limit order return value for placing new order
        args.setOrderId(0L);
        args.setLimitOrderReturnValue(null);
        // setting average price to null for updating dsx price, when cancelling order
        args.setAveragePrice(null);

        unlimitedRepeatableRequest("cancelAllOrders", tradeService::cancelAllOrders);
        logInfo("Cancelled all active account orders");
    }

    private void printOrderStatus(long orderStatus) {
        if (orderStatus == 0L) logInfo("Actual order status: Active");
        else if (orderStatus == 1L) logInfo("Actual order status: Filled");
        else if (orderStatus == 2L) logInfo("Actual order status: Killed");
        else logInfo("Actual order status: {}", orderStatus);
    }

    private boolean checkPriceBeforeOrder(BigDecimal orderPrice, BigDecimal dsxHighestPrice, BigDecimal stepToMove) {
        return orderPrice.subtract(dsxHighestPrice).abs().compareTo(stepToMove) > 0;
    }

    private boolean checkPriceAfterOrder(BigDecimal priceAfterOrder, BigDecimal rate, BigDecimal sensitivity) {
        return priceAfterOrder != null && rate.subtract(priceAfterOrder).compareTo(sensitivity) > 0;
    }

    private boolean checkVolumeBeforeOrder(BigDecimal volumeBeforeOrder, BigDecimal volumeToMove) {
        return volumeBeforeOrder.compareTo(volumeToMove) >= 0;
    }

    public boolean executeAlgorithm() throws Exception {
        PriceProperties priceConstants = args.getPriceProperties();

        Balance balance = unlimitedRepeatableRequest("getFunds", () ->
                getFunds(args.getDsxExchange()));

        logInfo("Account funds: {}", balance);
        //waiting for our price to be better than average price on supported exchanges
        awaitGoodPrice();

        //calculating data for placing order on our exchange
        BigDecimal dsxPriceWithAddition = args.getDsxPrice().add(priceConstants.getPriceAddition());

        BigDecimal volume = unlimitedRepeatableRequest("getFunds", () ->
                getFunds(args.getDsxExchange()).getAvailable().divide(dsxPriceWithAddition, priceConstants.getVolumeScale(),
                        RoundingMode.DOWN));

        // condition for not printing volume, when this is redundant
        if (volume.compareTo(LOW_VOLUME) > 0) {

            cancelAllOrders((DSXTradeService) args.getDsxTradeServiceRaw());
            volume = unlimitedRepeatableRequest("getFunds", () ->
                    getFunds(args.getDsxExchange()).getAvailable().divide(dsxPriceWithAddition, priceConstants.getVolumeScale(),
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
                    int orderStatus = unlimitedRepeatableRequest("getOrderStatus", () ->
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
            if (args.getLimitOrderReturnValue() == null) {
                //place new order
                BigDecimal finalVolume = volume;
                String limitOrderReturnValue = unlimitedRepeatableRequest("placeLimitOrder", () ->
                        args.getTradeService().placeLimitOrder(new LimitOrder(Order.OrderType.BID, finalVolume, CURRENCY_PAIR,
                                "", date, dsxPriceWithAddition)));
                logInfo("Order with id {} was placed", limitOrderReturnValue);
                args.setLimitOrderReturnValue(limitOrderReturnValue);
            }

            args.setOrderId(Long.parseLong(args.getLimitOrderReturnValue()));

            //sleep some time before checking order status
            TimeUnit.SECONDS.sleep(priceConstants.getWaitingTimeForOrderCheck());

            //get actual order status
            if (args.getOrderId() != 0L) {
                DSXOrderStatusResult result = unlimitedRepeatableRequest("getOrderStatus", () ->
                        args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()));
                printOrderStatus(result.getStatus());

                //get current DSX price
                BigDecimal dsxCurrentPrice = unlimitedRepeatableRequest("getBidOrderHighestPriceDSX", () ->
                        getBidOrderHighestPriceDSX(args.getDsxExchange()));

                // Order status == Filled - algorithm executed correctly
                if (result.getStatus() == 1) {
                    logInfo("Order was filled");
                    return true;
                }

                // if order status not filled - check that order is actual (top bid or so and price is good).
                BigDecimal volumeBeforeOrder = unlimitedRepeatableRequest("getVolumeBeforeVolume", () ->
                        getVolumeBeforeOrder(args.getDsxExchange(), result.getRate()));

                BigDecimal priceAfterOrder = unlimitedRepeatableRequest("getPriceAfterOrder", () ->
                        getPriceAfterOrder(args.getDsxExchange(), result.getRate()));

                // good means that difference between user's order price and order's price after is less than sensitivity
                boolean isPriceAfterOrderGood = checkPriceAfterOrder(priceAfterOrder, result.getRate(), priceConstants.getSensitivity());

                // good means that difference between user's order price and order's price before is less than step to move
                boolean isPriceBeforeOrderGood = checkPriceBeforeOrder(result.getRate(), dsxCurrentPrice, priceConstants.getStepToMove());

                boolean isVolumeGood = checkVolumeBeforeOrder(volumeBeforeOrder, priceConstants.getVolumeToMove());

                if ((priceAfterOrder == null
                        || dsxCurrentPrice.compareTo(priceAfterOrder.add(priceConstants.getSensitivity())) <= 0)
                        && (isPriceBeforeOrderGood || isVolumeGood || isPriceAfterOrderGood)) {
                    //if price is good and order needs to be replaced (with better price). check order status again
                    long orderStatus = unlimitedRepeatableRequest("getOrderStatus", () ->
                            args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()).getStatus());

                    printOrderStatus(orderStatus);
                    // if order status is not filled or killed, then cancel order so we can place another order
                    if (orderStatus != 1 && orderStatus != 2) {
                        unlimitedRepeatableRequest("cancelOrder", () ->
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

        while (args.getAveragePrice() == null || !isDSXPriceGood(priceProperties)) {
            //get new average price from other exchanges
            args.setAveragePrice(AVERAGE_PRICE.getAveragePrice(priceProperties.getTimestampForPriceUpdate(),
                    priceProperties.getPriceScale()));

            //get DSX price
            args.setDsxPrice(unlimitedRepeatableRequest("getBidOrderHighestPriceDSX", () ->
                    getBidOrderHighestPriceDSX(args.getDsxExchange())));

            if (args.getAveragePrice() != null) {
                logInfo("Average price: {}, dsxPrice: {}", args.getAveragePrice(), args.getDsxPrice());
                //if DSX price is bad
                if (!isDSXPriceGood(priceProperties)) {
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
                sleep("Sleep was interrupted");
            }
        }
    }

    private boolean isDSXPriceGood(PriceProperties priceProperties) {

        args.setAveragePrice(AVERAGE_PRICE.getAveragePrice(priceProperties.getTimestampForPriceUpdate(),
                priceProperties.getPriceScale()));
        BigDecimal averagePrice = args.getAveragePrice();
        BigDecimal dsxPrice = args.getDsxPrice();
        return averagePrice != null && averagePrice.compareTo(dsxPrice.multiply(priceProperties.getPricePercentage())) >= 0;
    }
}
