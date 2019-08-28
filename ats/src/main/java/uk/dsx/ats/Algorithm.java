package uk.dsx.ats;

import lombok.Value;
import org.apache.logging.log4j.Logger;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.marketdata.MarketDataService;
import uk.dsx.ats.data.AlgorithmArgs;
import uk.dsx.ats.data.PriceProperties;
import uk.dsx.ats.utils.DSXUtils;
import uk.dsx.ats.utils.MarketDataRepository;
import uk.dsx.ats.utils.OrderBookHelper;
import uk.dsx.ats.utils.TradeRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static uk.dsx.ats.AtsMain.*;
import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

@Value
class Algorithm {

    private static final BigDecimal LOW_VOLUME = new BigDecimal("0.1");

    interface CancelOrderPolicy {
        boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook);
    }

    AlgorithmArgs args;
    Logger logInfo;
    Logger logAudit;
    Date date;

    private final MarketDataRepository marketDataRepository;
    private final TradeRepository tradeRepository;
    private final List<CancelOrderPolicy> cancelOrderPolicies;

    Algorithm(AlgorithmArgs args, Logger logInfo, Logger logAudit, Date date) {
        this.args = args;
        this.logInfo = logInfo;
        this.logAudit = logAudit;
        this.date = date;

        this.marketDataRepository = new MarketDataRepository(args.getDsxExchange(), DSX_CURRENCY_PAIR);
        this.tradeRepository = new TradeRepository((DSXTradeService) args.getDsxTradeServiceRaw());

        this.cancelOrderPolicies = Arrays.asList(
                new StepToMove(args.getPriceProperties().getStepToMove()),
                new VolumeToMove(args.getPriceProperties().getVolumeToMove()),
                new Sensitivity(args.getPriceProperties().getSensitivity())
        );
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
                DSXOrderStatusResult order = tradeRepository.getOrderStatus(args.getOrderId());

                printOrderStatus(order.getStatus());

                // Order status == Filled - algorithm executed correctly
                if (order.getStatus() == 1) {
                    logInfo("Order was filled");
                    return true;
                }

                cancelOrderIfNeeded(order);
            }
        } catch (ExchangeException e) {
            logErrorWithException("Exchange exception: {}", e);
        }
        return false;
    }

    private void cancelOrderIfNeeded(DSXOrderStatusResult order) throws Exception {
        OrderBook orderBook = marketDataRepository.getOrderBook();

        if (cancelOrderPolicies.stream().noneMatch(policy -> policy.shouldCancelOrder(order, orderBook))) {
            return;
        }

        int status = tradeRepository.getOrderStatus(args.getOrderId()).getStatus();

        if (status != 1 && status != 2) {
            logInfo("Cancelling order, because better price exists");
            tradeRepository.cancelOrder(args.getLimitOrderReturnValue());
            args.setOrderId(0L);
            args.setAveragePrice(null);
        }

        args.setLimitOrderReturnValue(null);
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

    static class VolumeToMove implements CancelOrderPolicy {

        private final BigDecimal maxVolumeAbove;

        VolumeToMove(BigDecimal maxVolumeAbove) {
            this.maxVolumeAbove = maxVolumeAbove;
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            DSXUtils.logInfo("StepToMove checking");

            OrderBookHelper orderBookHelper = new OrderBookHelper(orderBook);
            BigDecimal bidVolumeAbove = orderBookHelper.bidVolumeAbove(order.getRate());

            if (bidVolumeAbove == null) {
                DSXUtils.logInfo("\tBids above are null");
                return false;
            }

            DSXUtils.logInfo("\tBid volume above order = {}; Maximum volume above = {}",
                    bidVolumeAbove, maxVolumeAbove);

            return bidVolumeAbove.compareTo(maxVolumeAbove) > 0;
        }

//        private boolean checkVolumeBeforeOrder(BigDecimal volumeBeforeOrder, BigDecimal volumeToMove) {
//            return volumeBeforeOrder.compareTo(volumeToMove) >= 0;
//        }
    }

    static class StepToMove implements CancelOrderPolicy {

        private final BigDecimal maxDistanceToBestBid;

        StepToMove(BigDecimal maxDistanceToBestBid) {
            this.maxDistanceToBestBid = maxDistanceToBestBid;
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            DSXUtils.logInfo("StepToMove checking");

            OrderBookHelper bookHelper = new OrderBookHelper(orderBook);
            BigDecimal bestBid = bookHelper.bestBidPrice();

            if (bestBid == null) {
                DSXUtils.logInfo("\tBest bid is null");
                return false;
            }

            BigDecimal distanceToBestBid = bestBid.subtract(order.getRate());

            DSXUtils.logInfo("\tBest bid price = {}; Distance to best bid = {}; Maximum distance = {}",
                    bestBid, distanceToBestBid, maxDistanceToBestBid);

            return distanceToBestBid.compareTo(maxDistanceToBestBid) > 0;
        }

        // good means that difference between user's order price and order's price before is less than step to move
//        private boolean checkPriceBeforeOrder(BigDecimal orderPrice, BigDecimal priceBeforeOrder, BigDecimal stepToMove) {
//            return priceBeforeOrder != null && orderPrice.subtract(priceBeforeOrder).abs().compareTo(stepToMove) > 0;
//        }
    }

    static class Sensitivity implements CancelOrderPolicy {

        private final BigDecimal maxDistanceToNextOrder;

        Sensitivity(BigDecimal maxDistanceToNextOrder) {
            this.maxDistanceToNextOrder = maxDistanceToNextOrder;
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            DSXUtils.logInfo("Sensitivity checking");

            OrderBookHelper orderBookHelper = new OrderBookHelper(orderBook);
            BigDecimal nextBidPrice = orderBookHelper.getBidPriceAfter(order.getRate());

            if (nextBidPrice == null) {
                DSXUtils.logInfo("\tThere is no any bid after order");
                return false;
            }

            BigDecimal distanceToNextOrder = order.getRate().subtract(nextBidPrice);

            DSXUtils.logInfo("\tNext bid price = {0}; Distance to next bid = {1}; Maximum distance = {2}",
                    nextBidPrice, distanceToNextOrder, maxDistanceToNextOrder);

            return distanceToNextOrder.compareTo(maxDistanceToNextOrder) > 0;
        }

//        good means that difference between user's order price and order's price after is less than sensitivity
//        private boolean checkPriceAfterOrder(BigDecimal priceAfterOrder, BigDecimal rate, BigDecimal sensitivity) {
//            return priceAfterOrder != null && rate.subtract(priceAfterOrder).compareTo(sensitivity) > 0;
//        }
    }
}
