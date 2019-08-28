package uk.dsx.ats.repositories;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.trade.LimitOrder;
import uk.dsx.ats.AtsMain;
import uk.dsx.ats.utils.DSXUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class AveragePriceRepository {

    private final List<ExchangeWrapper> exchanges;
    private final CurrencyPair pair;
    private final int scale;

    public AveragePriceRepository(List<Exchange> exchanges, CurrencyPair pair, int scale) {
        this.exchanges = exchanges.stream().map(ExchangeWrapper::new).collect(Collectors.toList());
        this.pair = pair;
        this.scale = scale;
    }

    public BigDecimal getAveragePrice() throws Exception {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal count = new BigDecimal(exchanges.size());

        for (ExchangeWrapper exchange : exchanges) {
            if (exchange.getNeedUpdate()) {
                Optional<LimitOrder> order = exchange.exchange.getMarketDataService().getOrderBook(pair).getBids().stream().findFirst();
                if (order.isPresent()) {
                    exchange.setLastPrice(order.get().getLimitPrice());
                } else {
                    exchange.setLastPrice(null);
                }
            }

            if (exchange.getLastPrice() != null) {
                sum = sum.add(exchange.getLastPrice());
                count = count.add(BigDecimal.ONE);
            }
        }

        if (count.equals(BigDecimal.ZERO)) {
            return null;
        } else {
            return sum.divide(count, scale, RoundingMode.DOWN);
        }
    }

    static class ExchangeWrapper {

        final Exchange exchange;

        private BigDecimal lastPrice;
        private long lastUpdate;
        private long updateDelay;

        ExchangeWrapper(Exchange exchange) {
            this.exchange = exchange;
            this.lastPrice = null;
            this.lastUpdate = 0L;
            this.updateDelay = DSXUtils.getRateLimitFromProperties(AtsMain.RATE_LIMIT_CONFIG,
                    exchange.getDefaultExchangeSpecification().getExchangeName());
        }

        boolean getNeedUpdate() {
            return lastUpdate + updateDelay < System.currentTimeMillis();
        }

        BigDecimal getLastPrice() {
            return lastPrice;
        }

        void setLastPrice(BigDecimal lastPrice) {
            this.lastPrice = lastPrice;
            this.lastUpdate = System.currentTimeMillis();
        }
    }
}
