package uk.dsx.ats.data;

import lombok.Data;
import org.knowm.xchange.Exchange;

import java.math.BigDecimal;

@Data
public class ExchangeData {
    private Exchange exchange;
    private volatile BigDecimal price;
    private volatile long timestamp;

    public ExchangeData(Exchange exchange) {
        this.exchange = exchange;
    }
}

