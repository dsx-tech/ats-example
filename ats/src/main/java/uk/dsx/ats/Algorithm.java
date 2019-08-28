package uk.dsx.ats;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.marketdata.OrderBook;
import uk.dsx.ats.data.PriceProperties;
import uk.dsx.ats.repositories.AccountRepository;
import uk.dsx.ats.repositories.AveragePriceRepository;
import uk.dsx.ats.repositories.MarketDataRepository;
import uk.dsx.ats.repositories.TradeRepository;
import uk.dsx.ats.utils.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

class Algorithm {

    enum OrderCheckingResult {
        ORDER_FILLED,
        NEED_REPLACE_ORDER,
        ACCEPTABLE_ORDER_PRICE
    }

    interface CancelOrderPolicy {
        boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook);
    }

    private static final BigDecimal LOW_VOLUME = new BigDecimal("0.1");

    private final MarketDataRepository marketDataRepository;
    private final TradeRepository tradeRepository;
    private final AccountRepository accountRepository;
    private final List<CancelOrderPolicy> cancelOrderPolicies;
    private final AveragePriceRepository averagePriceRepository;
    private final PriceProperties priceProperties;

    Algorithm(PriceProperties priceProperties, MarketDataRepository marketDataRepository, TradeRepository tradeRepository, AccountRepository accountRepository, AveragePriceRepository averagePriceRepository) {
        this.marketDataRepository = marketDataRepository;
        this.tradeRepository = tradeRepository;
        this.accountRepository = accountRepository;
        this.priceProperties = priceProperties;

        this.cancelOrderPolicies = Arrays.asList(
                new StepToMove(priceProperties.getStepToMove()),
                new VolumeToMove(priceProperties.getVolumeToMove()),
                new Sensitivity(priceProperties.getSensitivity())
        );
        this.averagePriceRepository = averagePriceRepository;
    }

    boolean execute() throws Exception {
        tradeRepository.cancelAllOrders();

        logInfo("Account funds: {}", accountRepository.getBalance());

        //waiting for our price to be better than average price on supported exchanges
        BigDecimal bestBidPrice = new PlaceOrderMonitor().awaitAcceptablePrice();

        //calculating data for placing order on our exchange
        BigDecimal orderPrice = bestBidPrice.add(priceProperties.getPriceAddition());
        BigDecimal orderVolume = calculateAvailableVolume(accountRepository.getBalance(), orderPrice, priceProperties.getVolumeScale());

        if (orderVolume.compareTo(LOW_VOLUME) < 0) {
            logError("Couldn't place order. Not enough money.");
            return true;
        }

        //placing order
        String orderId = tradeRepository.buyLimit(orderVolume, DSX_CURRENCY_PAIR, orderPrice);
        logInfo("Order with id {} was placed", orderId);

        OrderStateChecker orderChecker = new OrderStateChecker(Long.parseLong(orderId));

        if (orderChecker.awaitStateChanged() == OrderCheckingResult.ORDER_FILLED) {
            return true;
        } else {
            logInfo("Cancelling order");
            tradeRepository.cancelOrder(orderId);
            return false;
        }
    }

    private BigDecimal calculateAvailableVolume(Balance balance, BigDecimal orderPrice, int volumeScale) {
        return balance.getAvailable().divide(orderPrice, volumeScale, RoundingMode.DOWN);
    }

    private void logOrderStatus(int orderStatus) {
        switch (orderStatus) {
            case 0:     logInfo("Actual order status: Active"); break;
            case 1:     logInfo("Actual order status: Filled"); break;
            case 2:     logInfo("Actual order status: Killed"); break;
            default:    logInfo("Actual order status: {}", orderStatus); break;
        }
    }

    private BigDecimal getExchangeRate(CurrencyPair tradePair, CurrencyPair indicativePair) {
        if (tradePair.equals(indicativePair)) {
            return BigDecimal.ONE;
        }

        BigDecimal exchangeRate = marketDataRepository.getExchangeRate(tradePair, indicativePair);

        if (exchangeRate == null) {
            logInfo(String.format("Unable to get exchange rate for currencies: %s/%s", DSX_CURRENCY_PAIR.counter, EXCHANGES_CURRENCY_PAIR.counter));
            return null;
        } else {
            return exchangeRate.multiply(priceProperties.getFxPercentage());
        }
    }

    class PlaceOrderMonitor {

        BigDecimal awaitAcceptablePrice() throws Exception {
            while (true) {
                BigDecimal bestBidPrice = new OrderBookHelper(marketDataRepository.getOrderBook()).bestBidPrice();
                BigDecimal averagePrice = averagePriceRepository.getAveragePrice();

                if (isPriceAcceptable(bestBidPrice, averagePrice)) {
                    return bestBidPrice;
                }

                TimeUnit.MILLISECONDS.sleep(priceProperties.getAveragePriceUpdateTime());
            }
        }

        private boolean isPriceAcceptable(BigDecimal bestBid, BigDecimal averagePrice) {
            if (averagePrice == null) return false;

            BigDecimal exchangeRate = getExchangeRate(DSX_CURRENCY_PAIR, EXCHANGES_CURRENCY_PAIR);

            if (exchangeRate == null) {
                return false;
            } else {
                return bestBid.multiply(priceProperties.getPricePercentage().multiply(exchangeRate)).compareTo(averagePrice) <= 0;
            }
        }
    }

    class OrderStateChecker {

        private final long orderId;

        OrderStateChecker(long orderId) {
            this.orderId = orderId;
        }

        OrderCheckingResult awaitStateChanged() throws Exception {
            while (true) {
                OrderCheckingResult result = checkOrder();
                if (result == OrderCheckingResult.ACCEPTABLE_ORDER_PRICE) {
                    TimeUnit.SECONDS.sleep(priceProperties.getWaitingTimeForOrderCheck());
                } else {
                    return result;
                }
            }
        }

        private OrderCheckingResult checkOrder() throws Exception {
            DSXOrderStatusResult order = tradeRepository.getOrderStatus(orderId);

            logOrderStatus(order.getStatus());

            // Order status == Filled - algorithm executed correctly
            if (order.getStatus() == 1) {
                logInfo("Order was filled");
                return OrderCheckingResult.ORDER_FILLED;
            }

            OrderBook orderBook = marketDataRepository.getOrderBook();

            return cancelOrderPolicies.stream().anyMatch(policy -> policy.shouldCancelOrder(order, orderBook))
                    ? OrderCheckingResult.NEED_REPLACE_ORDER
                    : OrderCheckingResult.ACCEPTABLE_ORDER_PRICE;
        }
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

            return bidVolumeAbove.compareTo(maxVolumeAbove) >= 0;
        }
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
    }
}
