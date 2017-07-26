package uk.dsx.ats.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/**
 * @author Mikhail Wall
 */
@Value
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {

    @JsonProperty("DSXConfig")
    ExchangeProperties exchangeProperties;

    @JsonProperty("PriceConfig")
    PriceProperties priceProperties;
}
