package uk.dsx.ats.utils;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.marketdata.OrderBook;

import static uk.dsx.ats.utils.DSXUtils.PRICE_PROPERTIES;

public class MarketDataRepository {

    private final Exchange exchange;
    private final CurrencyPair currencyPair;

    public MarketDataRepository(Exchange exchange, CurrencyPair currencyPair) {
        this.exchange = exchange;
        this.currencyPair = currencyPair;
    }

    public OrderBook getOrderBook() throws Exception {
        return DSXUtils.unlimitedRepeatableRequest("getOrderBook",
                () -> exchange.getMarketDataService().getOrderBook(currencyPair, PRICE_PROPERTIES.getDsxAccountType()));
    }
}
