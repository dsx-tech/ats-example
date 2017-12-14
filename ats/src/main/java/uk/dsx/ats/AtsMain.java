package uk.dsx.ats;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.bitfinex.v1.BitfinexExchange;
import org.knowm.xchange.bitstamp.BitstampExchange;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.account.Balance;
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
import uk.dsx.ats.utils.FixerUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

public class AtsMain {

    public static final Logger logInfo = LogManager.getLogger("info-log");
    public static final Logger logAudit = LogManager.getLogger("audit-log");

    private static final String RATE_LIMIT_CONFIG = "rateLimit.json";

    private static final int REQUEST_TO_DSX_TIMEOUT_SECONDS = 10;
    private static final int REQUEST_TO_FIXER_DELAY_SECONDS = 60;

    static final AveragePrice AVERAGE_PRICE = new AveragePrice();
    private static final OrderBookWrapper ORDER_BOOK_WRAPPER = new OrderBookWrapper();
    private static ScheduledExecutorService EXECUTOR_SERVICE = null;
    private static ScheduledExecutorService DSX_EXECUTOR_SERVICE = null;
    private static ScheduledExecutorService FIXER_EXECUTOR_SERVICE;
    private static final ArrayList<ExchangeData> EXCHANGES = new ArrayList<>();

    private static BigDecimal getBidOrderHighestPrice(Exchange exchange) throws IOException {
        try {
            MarketDataService marketDataService = exchange.getMarketDataService();
            return marketDataService.getOrderBook(EXCHANGES_CURRENCY_PAIR).getBids().get(0).getLimitPrice();
        } catch (HttpStatusIOException e) {
            return null;
        }
    }

    private static void checkLiquidity(Algorithm algorithm) {
        Exchange dsxExchange = algorithm.getArgs().getDsxExchange();
        MarketDataService marketDataService = dsxExchange.getMarketDataService();
        String exchangeName = dsxExchange.getExchangeSpecification().getExchangeName();

        Runnable exchangeRun = () -> {
            try {
                ORDER_BOOK_WRAPPER.setOrderBook(marketDataService.getOrderBook(DSX_CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()));

                // if only our order is placed in order book, then cancel it
                if (ORDER_BOOK_WRAPPER.getOrderBook().getBids().size() == 1) {
                    algorithm.cancelAllOrders((DSXTradeService) dsxExchange.getTradeService());
                    logInfo("Cancelled all orders, because liquidity disappeared");
                }
            } catch (Throwable e) {
                logError("Cannot get {} orderbook", exchangeName, e.getMessage());
            }
        };
        DSX_EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor();
        DSX_EXECUTOR_SERVICE.scheduleWithFixedDelay(exchangeRun, 0, REQUEST_TO_DSX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void getFxRate(Algorithm algorithm) {
        Runnable getFxRate = () -> {
            try {
                algorithm.getArgs().setFxRate(FixerUtils.getRate(DSX_CURRENCY_PAIR.counter.getCurrencyCode(),
                        EXCHANGES_CURRENCY_PAIR.counter.getCurrencyCode()));
            } catch (Throwable e) {
                logError("Cannot get fx rates", e);
            }
        };
        FIXER_EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor();
        FIXER_EXECUTOR_SERVICE.scheduleWithFixedDelay(getFxRate, 0, REQUEST_TO_FIXER_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    static Balance getFunds(Exchange exchange, Currency currency) throws IOException {
        AccountService accountService = exchange.getAccountService();
        return accountService.getAccountInfo().getWallet().getBalance(currency);
    }

    private static void initExchanges(ArrayList<Class> classes) {
        for (Class c : classes) {
            try {
                EXCHANGES.add(new ExchangeData(ExchangeFactory.INSTANCE.createExchange(c.getName())));
            } catch (Throwable e) {
                logError("Cannot init {} exchange, error: {}", e.getMessage(), c.getName());
            }
        }
        EXECUTOR_SERVICE = Executors.newScheduledThreadPool(EXCHANGES.size());

        AVERAGE_PRICE.setExchanges(EXCHANGES);
    }

    private static void calculateAveragePriceAsync() {

        for (ExchangeData exchange : AVERAGE_PRICE.getExchanges()) {
            String exchangeName = exchange.getExchange().getExchangeSpecification().getExchangeName();
            Runnable exchangeRun = () -> {
                try {
                    exchange.setPrice(getBidOrderHighestPrice(exchange.getExchange()));
                    exchange.setTimestamp(Instant.now().getEpochSecond());
                } catch (Throwable e) {
                    logError("Cannot get {} price: {}", exchangeName, e.getMessage());
                }
            };
            EXECUTOR_SERVICE.scheduleWithFixedDelay(exchangeRun, 0,
                    DSXUtils.getRateLimitFromProperties(RATE_LIMIT_CONFIG, exchangeName), TimeUnit.SECONDS);
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

            initExchanges(new ArrayList<>(Arrays.asList(KrakenExchange.class, BitfinexExchange.class, BitstampExchange.class)));
            calculateAveragePriceAsync();
            checkLiquidity(algorithm);
            getFxRate(algorithm);

            while (true) {
                algorithm.cancelAllOrders(dsxTradeService);
                logInfo("Cancelled all active orders");

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
            DSX_EXECUTOR_SERVICE.shutdown();
            FIXER_EXECUTOR_SERVICE.shutdown();
            logInfo("ATS finished");
        }
    }
}

