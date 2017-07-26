package uk.dsx.ats.data;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;

/**
 * @author Mikhail Wall
 */

@Data
public class AveragePrice {

    private ArrayList<ExchangeData> exchanges;

    public BigDecimal getAveragePrice(long timestampForPriceUpdate, int priceScale) {
        BigDecimal averagePrice = BigDecimal.ZERO;
        int counterForPriceResponses = 0;
        long timeNow = Instant.now().getEpochSecond();
        for (ExchangeData exchange : getExchanges()) {
            BigDecimal price = exchange.getPrice();
            long timestamp = exchange.getTimestamp();

            if (price != null && timeNow - timestamp < timestampForPriceUpdate) {
                averagePrice = averagePrice.add(price);
                counterForPriceResponses++;
            }
        }

        if (averagePrice.equals(BigDecimal.ZERO)) return null;

        return averagePrice.divide(new BigDecimal(counterForPriceResponses), priceScale, RoundingMode.DOWN);
    }
}
