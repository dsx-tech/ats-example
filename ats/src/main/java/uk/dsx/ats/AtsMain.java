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
import uk.dsx.ats.utils.DSXUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

public class AtsMain {

    public static final Logger logInfo = LogManager.getLogger("info-log");
    public static final Logger logAudit = LogManager.getLogger("audit-log");

    public static final CurrencyPair CURRENCY_PAIR = CurrencyPair.BTC_USD;

    public static final String RATE_LIMIT_CONFIG = "rateLimit.json";

    public static final AveragePrice AVERAGE_PRICE = new AveragePrice();
    public static ScheduledExecutorService EXECUTOR_SERVICE = null;
    public static ArrayList<ExchangeData> EXCHANGES = new ArrayList<>();

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
        return marketDataService.getOrderBook(CURRENCY_PAIR, PRICE_PROPERTIES.getDsxAccountType()).getBids().get(0).getLimitPrice();
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

        List<LimitOrder> orders = marketDataService.getOrderBook(CURRENCY_PAIR).getBids();
        for (LimitOrder order : orders) {
            if (order.getLimitPrice().compareTo(price) > 0) {
                volume = volume.add(order.getTradableAmount());
            }
        }
        return volume;
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

                Balance balance = getFunds(dsxExchange);
                logInfo("Account funds: {}", balance);

                initExchanges(new ArrayList<>(Arrays.asList(KrakenExchange.class, BitfinexExchange.class, BitstampExchange.class)));
                calculateAveragePriceAsync();

                boolean isAlgorithmEnded = false;
                while (!isAlgorithmEnded) {
                    isAlgorithmEnded = algorithm.executeAlgorithm();
                };
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

