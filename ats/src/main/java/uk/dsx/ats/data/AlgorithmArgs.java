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
    PriceProperties priceProperties;
    BigDecimal dsxPrice;
    BigDecimal averagePrice;
    volatile BigDecimal fxRate;
    String limitOrderReturnValue;
    long orderId;
    Exchange dsxExchange;
    TradeService tradeService;
    DSXTradeServiceRaw dsxTradeServiceRaw;
    BigDecimal orderPrice;

    public AlgorithmArgs(PriceProperties priceProperties, Exchange dsxExchange, TradeService tradeService,
                         DSXTradeServiceRaw dsxTradeServiceRaw) {
        this.priceProperties = priceProperties;
        this.dsxExchange = dsxExchange;
        this.tradeService = tradeService;
        this.dsxTradeServiceRaw = dsxTradeServiceRaw;
    }
}
