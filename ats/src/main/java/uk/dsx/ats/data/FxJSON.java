package uk.dsx.ats.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FxJSON {
    @JsonProperty("base")
    public String base;

    @JsonProperty("date")
    public String date;

    @JsonProperty("rates")
    public Map<String, BigDecimal> rates;
}
