package uk.dsx.ats.repositories;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.service.marketdata.MarketDataService;
import uk.dsx.ats.utils.DSXUtils;

import java.math.BigDecimal;

import static uk.dsx.ats.utils.DSXUtils.PRICE_PROPERTIES;

public class MarketDataRepository {

    private final MarketDataService service;
    private final CurrencyPair currencyPair;

    private ExchangeRateHelper exchangeHalper;

    public MarketDataRepository(MarketDataService service, CurrencyPair currencyPair) {
        this.service = service;
        this.currencyPair = currencyPair;
    }

    public BigDecimal getExchangeRate(CurrencyPair indicativePair) throws Exception {
        if (exchangeHalper != null) {
            return exchangeHalper.getRate();
        }

        ExchangeRateHelper helper = new ExchangeRateHelper(new CurrencyPair(currencyPair.counter, indicativePair.counter), false);
        BigDecimal exchangeRate = helper.getRate();

        if (exchangeRate != null) {
            exchangeHalper = helper;
            return exchangeRate;
        } else {
            exchangeHalper = new ExchangeRateHelper(new CurrencyPair(indicativePair.counter, currencyPair.counter), true);
            ;
            return exchangeHalper.getRate();
        }
    }

    public OrderBook getOrderBook() throws Exception {
        return DSXUtils.unlimitedRepeatableRequest("getOrderBook",
                () -> service.getOrderBook(currencyPair, PRICE_PROPERTIES.getDsxAccountType()));
    }

    class ExchangeRateHelper {
        private final CurrencyPair pair;
        private final boolean isInverted;

        ExchangeRateHelper(CurrencyPair pair, boolean isInverted) {
            this.pair = pair;
            this.isInverted = isInverted;
        }

        BigDecimal getRate() throws Exception {
            try {
                BigDecimal price = DSXUtils.unlimitedRepeatableRequest("getTicker",
                        () -> service.getTicker(pair, PRICE_PROPERTIES.getDsxAccountType())).getLast();

                return isInverted ? BigDecimal.ONE.divide(price) : price;
            } catch (Exception e) {
                return null;
            }
        }
    }
}