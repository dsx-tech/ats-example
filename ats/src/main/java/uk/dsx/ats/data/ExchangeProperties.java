package uk.dsx.ats.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * @author Mikhail Wall
 */

@Value
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeProperties {
    String url;
    String secretKey;
    String apiKey;
}
