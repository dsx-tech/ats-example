package uk.dsx.ats;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.bitfinex.v1.BitfinexExchange;
import org.knowm.xchange.bitstamp.BitstampExchange;

import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.kraken.KrakenExchange;

import uk.dsx.ats.repositories.AccountRepository;
import uk.dsx.ats.repositories.AveragePriceRepository;
import uk.dsx.ats.repositories.MarketDataRepository;
import uk.dsx.ats.repositories.TradeRepository;
import uk.dsx.ats.utils.*;

import java.util.*;
import java.util.concurrent.*;

import static uk.dsx.ats.utils.DSXUtils.*;

/**
 * @author Mikhail Wall
 */

public class AtsMain {

    public static final Logger logInfo = LogManager.getLogger("info-log");
    public static final Logger logAudit = LogManager.getLogger("audit-log");

    public static final String RATE_LIMIT_CONFIG = "rateLimit.json";

    private static final int REQUEST_TO_DSX_TIMEOUT_SECONDS = 10;
    private static final int REQUEST_TO_FIXER_DELAY_SECONDS = 60;

    public static void main(String[] args) throws Exception {
        Algorithm algorithm;

        TradeRepository tradeRepository;

        try {
            Exchange dsxExchange = DSXUtils.createExchange();
            DSXTradeService dsxTradeService = (DSXTradeService) dsxExchange.getTradeService();
            tradeRepository = new TradeRepository(dsxTradeService);

            algorithm = new Algorithm(PRICE_PROPERTIES,
                    new MarketDataRepository(dsxExchange.getMarketDataService(), DSX_CURRENCY_PAIR),
                    tradeRepository,
                    new AccountRepository(dsxExchange.getAccountService(), DSX_CURRENCY_PAIR.counter),
                    new AveragePriceRepository(
                            Arrays.asList(new KrakenExchange(), new BitfinexExchange(), new BitstampExchange()),
                            EXCHANGES_CURRENCY_PAIR, PRICE_PROPERTIES.getPriceScale()));
        } catch (Exception e) {
            logErrorWithException("Failed to init DSX connector, error: {}", e);
            return;
        }

        try {
            logInfo("ATS started");

            while (!algorithm.execute()) {
                TimeUnit.SECONDS.sleep(PRICE_PROPERTIES.getWaitingTimeForCheckingAccountFunds());
            }
        } catch (Exception e) {
            logErrorWithException("Something bad happened, error message:", e);
        } finally {
            tradeRepository.cancelAllOrders();
            logInfo("ATS finished");
        }
    }
}

