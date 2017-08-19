package uk.dsx.ats.data;

import lombok.Getter;
import lombok.Setter;
import org.knowm.xchange.dto.marketdata.OrderBook;

/**
 * @author Mikhail Wall
 */

public class OrderBookWrapper {

    @Setter
    @Getter
    private OrderBook orderBook;
}
