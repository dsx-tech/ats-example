package uk.dsx.ats;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.bitfinex.v1.BitfinexExchange;
import org.knowm.xchange.bitstamp.BitstampExchange;
import org.knowm.xchange.currency.CurrencyPair;

import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;

import si.mazi.rescu.HttpStatusIOException;
import uk.dsx.ats.data.AlgorithmArgs;
import uk.dsx.ats.data.AveragePrice;
import uk.dsx.ats.data.ExchangeData;
import uk.dsx.ats.data.OrderBookWrapper;
import uk.dsx.ats.utils.DSXUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

public class AtsMain {


    public static final Logger logInfo = LogManager.getLogger("info-log");
    public static final Logger logAudit = LogManager.getLogger("audit-log");

    public static final CurrencyPair CURRENCY_PAIR = CurrencyPair.BTC_USD;

    public static final String RATE_LIMIT_CONFIG = "rateLimit.json";

    private static final int REQUEST_TO_DSX_TIMEOUT_SECONDS = 10;

    public static final AveragePrice AVERAGE_PRICE = new AveragePrice();
    public static final OrderBookWrapper ORDER_BOOK_WRAPPER = new OrderBookWrapper();
    public static ScheduledExecutorService EXECUTOR_SERVICE = null;
    public static final ArrayList<ExchangeData> EXCHANGES = new ArrayList<>();


    public static BigDecimal getBidOrderHighestPrice(Exchange exchange) throws IOException {
        try {
            MarketDataService marketDataService = exchange.getMarketDataService();
            return marketDataService.getOrderBook(CURRENCY_PAIR).getBids().get(0).getLimitPrice();
        } catch (HttpStatusIOException e) {
            return null;
        }
    }

    public static BigDecimal getBidOrderHighestPriceDSX(Exchange exchange) throws IOException {
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

    public static void checkLiquidity(Algorithm algorithm) {
        Exchange exchange = algorithm.getArgs().getDsxExchange();
        MarketDataService marketDataService = exchange.getMarketDataService();
        String exchangeName = exchange.getExchangeSpecification().getExchangeName();

        Runnable exchangeRun = () -> {
            try {
                ORDER_BOOK_WRAPPER.setOrderBook(marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()));

                // if only our order is placed in order book, then cancel it
                if (ORDER_BOOK_WRAPPER.getOrderBook().getAsks().isEmpty() && ORDER_BOOK_WRAPPER.getOrderBook().getBids().size() == 1) {
                    algorithm.cancelAllOrders((DSXTradeService) exchange.getTradeService());
                    logInfo("Cancelled all orders, because liquidity disappeared");
                }
            } catch (Exception e) {
                logError("Cannot get {} orderbook", exchangeName, e.getMessage());
            }
        };
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleAtFixedRate(exchangeRun, 0, REQUEST_TO_DSX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static Balance getFunds(Exchange exchange) throws IOException {
        AccountService accountService = exchange.getAccountService();
        return accountService.getAccountInfo().getWallet().getBalance(CURRENCY_PAIR.counter);
    }

    public static void initExchanges(ArrayList<Class> classes) throws IOException {
        for (Class c : classes) {
            try {
                EXCHANGES.add(new ExchangeData(ExchangeFactory.INSTANCE.createExchange(c.getName())));
            } catch (Exception e) {
                logError("Cannot init {} exchange, error: {}", e.getMessage(), c.getName());
            }
        }
        EXECUTOR_SERVICE = Executors.newScheduledThreadPool(EXCHANGES.size());

        AVERAGE_PRICE.setExchanges(EXCHANGES);
    }

    public static void calculateAveragePriceAsync() throws IOException {

        for (ExchangeData exchange : AVERAGE_PRICE.getExchanges()) {
            String exchangeName = exchange.getExchange().getExchangeSpecification().getExchangeName();
            Runnable exchangeRun = () -> {
                try {
                    exchange.setPrice(getBidOrderHighestPrice(exchange.getExchange()));
                    exchange.setTimestamp(Instant.now().getEpochSecond());
                } catch (IOException e) {
                    logError("Cannot get {} price: {}", exchangeName, e.getMessage());
                }
            };
            EXECUTOR_SERVICE.scheduleAtFixedRate(exchangeRun, 0,
                    DSXUtils.getRateLimitFromProperties(RATE_LIMIT_CONFIG, exchangeName), TimeUnit.SECONDS);
        }
    }

    public static BigDecimal getVolumeBeforeOrder(Exchange exchange, BigDecimal price) throws IOException {
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

    public static BigDecimal getPriceAfterOrder(Exchange exchange, BigDecimal price) throws IOException {
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

    private static void sleep(String interruptedMessage) {
        try {
            TimeUnit.SECONDS.sleep(REQUEST_TO_DSX_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            if (interruptedMessage != null)
                logInfo.warn(interruptedMessage);
        }
    }

    public static void main(String[] args) throws Exception {
        Exchange dsxExchange;
        TradeService tradeService;
        DSXTradeService dsxTradeService;
        Algorithm algorithm;
        AlgorithmArgs algorithmArgs;

        try {
            dsxExchange = DSXUtils.createExchange();
            tradeService = dsxExchange.getTradeService();
            dsxTradeService = (DSXTradeService) tradeService;
            algorithmArgs = new AlgorithmArgs(PRICE_PROPERTIES, dsxExchange, tradeService, dsxTradeService);
            algorithm = new Algorithm(algorithmArgs, logInfo, logAudit, new Date());
        } catch (Exception e) {
            logErrorWithException("Failed to init DSX connector, error: {}", e);
            return;
        }

        try {
            logInfo("ATS started");

            while (true) {
                algorithm.cancelAllOrders(dsxTradeService);
                logInfo("Cancelled all active orders");

                initExchanges(new ArrayList<>(Arrays.asList(KrakenExchange.class, BitfinexExchange.class, BitstampExchange.class)));
                calculateAveragePriceAsync();
                checkLiquidity(algorithm);

                boolean isAlgorithmEnded = false;
                while (!isAlgorithmEnded) {
                    isAlgorithmEnded = algorithm.executeAlgorithm();
                }
                TimeUnit.SECONDS.sleep(PRICE_PROPERTIES.getWaitingTimeForCheckingAccountFunds());
            }
        } catch (Exception e) {
            logErrorWithException("Something bad happened, error message: {}", e);
        } finally {
            algorithm.cancelAllOrders(dsxTradeService);
            EXECUTOR_SERVICE.shutdown();
            logInfo("ATS finished");
        }
    }
}

