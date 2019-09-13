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
                new SingleBidRow(),
                new StepToMove(priceProperties.getStepToMove()),
                new VolumeToMove(priceProperties.getVolumeToMove()),
                new Sensitivity(priceProperties.getSensitivity()),
                new PriceHasBeenChanged()
        );
        this.averagePriceRepository = averagePriceRepository;
    }

    boolean execute() throws Exception {
        tradeRepository.cancelAllOrders();

        logInfo("Account funds: {}", accountRepository.getBalance());

        //waiting for our price to be better than average price on supported exchanges
        BigDecimal bestBidPrice = new PriceMonitor().awaitAcceptablePrice();

        //calculating data for placing order on our exchange
        BigDecimal orderPrice = bestBidPrice.add(priceProperties.getPriceAddition());
        BigDecimal orderVolume = calculateAvailableVolume(accountRepository.getBalance(), orderPrice, priceProperties.getVolumeScale());

        if (orderVolume.compareTo(priceProperties.getMinOrderSize()) < 0) {
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

    private BigDecimal getExchangeRate(CurrencyPair tradePair, CurrencyPair indicativePair) throws Exception {
        if (tradePair.equals(indicativePair)) {
            return BigDecimal.ONE;
        }

        BigDecimal exchangeRate = marketDataRepository.getExchangeRate(tradePair, indicativePair);

        if (exchangeRate == null) {
            logInfo(String.format("\t Unable to get exchange rate for currencies: %s/%s", DSX_CURRENCY_PAIR.counter, EXCHANGES_CURRENCY_PAIR.counter));
            return null;
        } else {
            return exchangeRate.multiply(priceProperties.getFxPercentage());
        }
    }

    /**
     * Check if averagePrice > dsx.bestBid * pricePercentage * exchangeRate
     */
    class PriceMonitor {

        BigDecimal awaitAcceptablePrice() throws Exception {
            while (true) {
                logInfo(" - Average price is checking");
                BigDecimal bestBidPrice = new OrderBookHelper(marketDataRepository.getOrderBook()).bestBidPrice();
                BigDecimal averagePrice = averagePriceRepository.getAveragePrice();

                if (isPriceAcceptable(bestBidPrice, averagePrice)) {
                    return bestBidPrice;
                }
                TimeUnit.MILLISECONDS.sleep(priceProperties.getAveragePriceUpdateTime());
            }
        }

        boolean isPriceAcceptable(BigDecimal bestBid, BigDecimal averagePrice) throws Exception {
            if (averagePrice == null) {
                logInfo("\t Can't calculate average price");
                return false;
            }

            if (bestBid == null) {
                logInfo("\t Low DSX liquidity for {}", PRICE_PROPERTIES.getDsxCurrencyPair());
                return false;
            }

            BigDecimal exchangeRate = getExchangeRate(DSX_CURRENCY_PAIR, EXCHANGES_CURRENCY_PAIR);

            if (exchangeRate == null) {
                logInfo("\t Can't access to exchange rate");
                return false;
            } else {
                BigDecimal pricePercentage = priceProperties.getPricePercentage();
                BigDecimal bidWithOffset = bestBid.multiply(pricePercentage).multiply(exchangeRate);
                logInfo("\t Average price = {}; Relative offset = {}; Best bid = {} (multiplied = {})", averagePrice, pricePercentage, bestBid, bidWithOffset);
                return averagePrice.compareTo(bidWithOffset) > 0;
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
                logInfo("");
                logInfo("================ Checking order state");
                OrderCheckingResult result = checkOrder();
                if (result == OrderCheckingResult.ACCEPTABLE_ORDER_PRICE) {
                    logInfo("All conditions are good.");
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
            } else {
                logInfo("Price = {}; Volume = {}/{}", order.getRate(), order.getRemainingVolume(), order.getVolume());
            }

            OrderBook orderBook = marketDataRepository.getOrderBook();

            return cancelOrderPolicies.stream().anyMatch(policy -> policy.shouldCancelOrder(order, orderBook))
                    ? OrderCheckingResult.NEED_REPLACE_ORDER
                    : OrderCheckingResult.ACCEPTABLE_ORDER_PRICE;
        }
    }

    /**
     * The order has to be cancelled if: there is no any other orders (by different prices)
     */
    static class SingleBidRow implements CancelOrderPolicy {

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            return orderBook.getBids().size() < 2;
        }
    }

    /**
     * The order has to be cancelled if initial conditions are broken
     */
    class PriceHasBeenChanged implements CancelOrderPolicy {

        private final PriceMonitor priceMonitor;

        PriceHasBeenChanged() {
            priceMonitor = new PriceMonitor();
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            logInfo(" - Average price is checking");
            OrderBookHelper orderBookHelper = new OrderBookHelper(orderBook);
            try {
                BigDecimal averagePrice = averagePriceRepository.getAveragePrice();
                return !priceMonitor.isPriceAcceptable(orderBookHelper.bestBidPrice(), averagePrice);
            } catch (Exception e) {
                logError("\t Impossible to check average price: {}", e);
                return false;
            }
        }
    }

    /**
     * The order has to be cancelled if: sum(betterBidOrders.volume) > volumeToMove
     */
    static class VolumeToMove implements CancelOrderPolicy {

        private final BigDecimal maxVolumeAbove;

        VolumeToMove(BigDecimal maxVolumeAbove) {
            this.maxVolumeAbove = maxVolumeAbove;
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            logInfo(" - VolumeToMove checking");

            OrderBookHelper orderBookHelper = new OrderBookHelper(orderBook);
            BigDecimal bidVolumeAbove = orderBookHelper.bidVolumeAbove(order.getRate());

            if (bidVolumeAbove == null) {
                logInfo("\t Bids above are null");
                return false;
            }

            logInfo("\t Bid volume above order = {}; Maximum volume above = {}",
                    bidVolumeAbove, maxVolumeAbove);

            return bidVolumeAbove.compareTo(maxVolumeAbove) >= 0;
        }
    }

    /**
     * The order has to be cancelled if:  bestBidOrder.price - ourOrder.price > stepToMove
     */
    static class StepToMove implements CancelOrderPolicy {

        private final BigDecimal maxDistanceToBestBid;

        StepToMove(BigDecimal maxDistanceToBestBid) {
            this.maxDistanceToBestBid = maxDistanceToBestBid;
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            logInfo(" - StepToMove checking");

            OrderBookHelper bookHelper = new OrderBookHelper(orderBook);
            BigDecimal bestBid = bookHelper.bestBidPrice();

            if (bestBid == null) {
                logInfo("\t Best bid is null");
                return false;
            }

            BigDecimal distanceToBestBid = bestBid.subtract(order.getRate());

            logInfo("\t Best bid price = {}; Distance to best bid = {}; Maximum distance = {}",
                    bestBid, distanceToBestBid, maxDistanceToBestBid);

            return distanceToBestBid.compareTo(maxDistanceToBestBid) > 0;
        }
    }

    /**
     * The order has to be cancelled if:  ourOrder.price - nextBidOrder.price > sensitivity
     */
    static class Sensitivity implements CancelOrderPolicy {

        private final BigDecimal maxDistanceToNextOrder;

        Sensitivity(BigDecimal maxDistanceToNextOrder) {
            this.maxDistanceToNextOrder = maxDistanceToNextOrder;
        }

        @Override
        public boolean shouldCancelOrder(DSXOrderStatusResult order, OrderBook orderBook) {
            logInfo(" - Sensitivity checking");

            OrderBookHelper orderBookHelper = new OrderBookHelper(orderBook);
            BigDecimal nextBidPrice = orderBookHelper.getBidPriceAfter(order.getRate());

            if (nextBidPrice == null) {
                logInfo("\t There is no any bid after order");
                return false;
            }

            BigDecimal distanceToNextOrder = order.getRate().subtract(nextBidPrice);

            logInfo("\t Next bid price = {}; Distance to next bid (distanceToNextOrder) = {}; Maximum distance (Sensitivity) = {}",
                    nextBidPrice, distanceToNextOrder, maxDistanceToNextOrder);

            return distanceToNextOrder.compareTo(maxDistanceToNextOrder) > 0;
        }
    }
}
