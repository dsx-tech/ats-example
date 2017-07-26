package uk.dsx.ats.data;

import lombok.Data;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dsx.service.DSXTradeServiceRaw;
import org.knowm.xchange.service.trade.TradeService;

import java.math.BigDecimal;

/**
 * @author Mikhail Wall
 */

@Data
public class AlgorithmArgs {
    PriceProperties priceConstants;
    BigDecimal dsxPrice;
    BigDecimal averagePrice;
    String limitOrderReturnValue;
    long orderId;
    Exchange dsxExchange;
    TradeService tradeService;
    DSXTradeServiceRaw dsxTradeServiceRaw;

    public AlgorithmArgs(PriceProperties priceConstants, Exchange dsxExchange, TradeService tradeService,
                         DSXTradeServiceRaw dsxTradeServiceRaw) {
        this.priceConstants = priceConstants;
        this.dsxExchange = dsxExchange;
        this.tradeService = tradeService;
        this.dsxTradeServiceRaw = dsxTradeServiceRaw;
    }
}
