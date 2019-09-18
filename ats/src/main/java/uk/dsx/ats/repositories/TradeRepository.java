package uk.dsx.ats.repositories;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.LimitOrder;
import uk.dsx.ats.utils.DSXUtils;

import java.math.BigDecimal;
import java.util.Date;

public class TradeRepository {

    private final DSXTradeService tradeService;

    public TradeRepository(DSXTradeService tradeService) {
        this.tradeService = tradeService;
    }

    public DSXOrderStatusResult getOrderStatus(long orderId) throws Exception {
        return DSXUtils.unlimitedRepeatableRequest("getOrderStatus", () -> tradeService.getOrderStatus(orderId));
    }

    public void cancelOrder(String orderId) throws Exception {
        DSXUtils.unlimitedRepeatableRequest("cancelOrder", () -> tradeService.cancelOrder(orderId));
    }

    public String buyLimit(BigDecimal volume, CurrencyPair pair, BigDecimal price) throws Exception {
        LimitOrder order = new LimitOrder(Order.OrderType.BID, volume, pair, "", new Date(), price);
        return DSXUtils.unlimitedRepeatableRequest("placeLimitOrder", () -> tradeService.placeLimitOrder(order));
    }

    public void cancelAllOrders() throws Exception {
        DSXUtils.unlimitedRepeatableRequest("cancelAllOrders", tradeService::cancelAllOrders);
    }
}
