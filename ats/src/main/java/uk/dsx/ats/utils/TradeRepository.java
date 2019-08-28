package uk.dsx.ats.utils;

import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeService;

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
}
