package uk.dsx.ats.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @author Mikhail Wall on 6/27/17.
 */

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PriceProperties {

    public static final BigDecimal DEFAULT_STEP_TO_MOVE = new BigDecimal("0.001");
    public static final BigDecimal DEFAULT_VOLUME_TO_MOVE = new BigDecimal("0.05");
    public static final long DEFAULT_AVERAGE_PRICE_UPDATE_TIME = 2000L;
    public static final BigDecimal DEFAULT_PRICE_PERCENTAGE = new BigDecimal("1.01");
    public static final int DEFAULT_VOLUME_SCALE = 4;
    public static final BigDecimal DEFAULT_PRICE_ADDITION = new BigDecimal("0.01");
    public static final long DEFAULT_TIMESTAMP_FOR_PRICE_UPDATE = 10L;
    public static final int DEFAULT_PRICE_SCALE = 5;
    public static final long DEFAULT_WAITING_TIME_FOR_ORDER_CHECK = 5L;
    public static final String DEFAULT_ACCOUNT_TYPE = "LIVE";
    public static final int DEFAULT_NEW_ORDER_TIME = 300;
    public static final BigDecimal DEFAULT_SENSITIVITY = new BigDecimal("5");

    BigDecimal pricePercentage = DEFAULT_PRICE_PERCENTAGE;
    int priceScale = DEFAULT_PRICE_SCALE;
    int volumeScale = DEFAULT_VOLUME_SCALE;
    BigDecimal priceAddition = DEFAULT_PRICE_ADDITION;
    long averagePriceUpdateTime = DEFAULT_AVERAGE_PRICE_UPDATE_TIME;
    long timestampForPriceUpdate = DEFAULT_TIMESTAMP_FOR_PRICE_UPDATE;
    String dsxAccountType = DEFAULT_ACCOUNT_TYPE;
    BigDecimal stepToMove = DEFAULT_STEP_TO_MOVE;
    BigDecimal volumeToMove = DEFAULT_VOLUME_TO_MOVE;
    long waitingTimeForOrderCheck = DEFAULT_WAITING_TIME_FOR_ORDER_CHECK;
    long waitingTimeForCheckingAccountFunds = DEFAULT_NEW_ORDER_TIME;
    BigDecimal sensitivity = DEFAULT_SENSITIVITY;
}
