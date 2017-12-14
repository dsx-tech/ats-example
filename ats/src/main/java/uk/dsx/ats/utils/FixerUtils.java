package uk.dsx.ats.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import uk.dsx.ats.AtsMain;
import uk.dsx.ats.data.FxJSON;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.net.URL;

public class FixerUtils {
    private static final String FIXER_API_URL = "http://api.fixer.io/latest?";

    public static @Nullable
    BigDecimal getRate(String baseCurrency, String quoteCurrency) {
        try {
            URL url = new URL(String.format(FIXER_API_URL + "base=%s&symbols=%s", baseCurrency, quoteCurrency));
            ObjectMapper objectMapper = new ObjectMapper();
            final FxJSON fxJSON = objectMapper.readValue(url, FxJSON.class);
            return fxJSON.rates.get(quoteCurrency);
        } catch (Exception e) {
            AtsMain.logInfo.info("Unable to receive fiat rates from fixer.io.", e);
            return null;
        }
    }
}
