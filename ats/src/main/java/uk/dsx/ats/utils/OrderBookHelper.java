package uk.dsx.ats.utils;

import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.trade.LimitOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class OrderBookHelper {

    private final OrderBook orderBook;

    public OrderBookHelper(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    public boolean hasBids() {
        return !orderBook.getBids().isEmpty();
    }

    public BigDecimal bestBidPrice() {
        return hasBids() ? orderBook.getBids().get(0).getLimitPrice() : null;
    }

    public List<LimitOrder> bidOrdersAbove(BigDecimal price) {
        return orderBook.getBids().stream()
                .filter( o -> o.getLimitPrice().compareTo(price) > 0)
                .collect(Collectors.toList());
    }

    public BigDecimal getBidPriceAfter(BigDecimal price) {
        for (LimitOrder order: orderBook.getBids()) {
            if (order.getLimitPrice().compareTo(price) < 0) {
                return  order.getLimitPrice();
            }
        }
        return null;
    }

     public BigDecimal bidVolumeAbove(BigDecimal price) {
        return bidOrdersAbove(price).stream()
                .map(LimitOrder::getOriginalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
