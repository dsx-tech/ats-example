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

    public MarketDataRepository(MarketDataService service, CurrencyPair currencyPair) {
        this.service = service;
        this.currencyPair = currencyPair;
    }

    public BigDecimal getExchangeRate(CurrencyPair tradePair, CurrencyPair indicativePair) throws Exception {
        return DSXUtils.unlimitedRepeatableRequest("getTicker",
                () -> service.getTicker(new CurrencyPair(tradePair.counter, indicativePair.counter), PRICE_PROPERTIES.getDsxAccountType()).getLast());
    }

    public OrderBook getOrderBook() throws Exception {
        return DSXUtils.unlimitedRepeatableRequest("getOrderBook",
                () -> service.getOrderBook(currencyPair, PRICE_PROPERTIES.getDsxAccountType()));
    }
}