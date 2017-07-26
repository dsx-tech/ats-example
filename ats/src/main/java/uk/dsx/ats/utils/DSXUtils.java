package uk.dsx.ats.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.dsx.DSXExchange;
import uk.dsx.ats.AtsMain;
import uk.dsx.ats.data.Config;
import uk.dsx.ats.data.ExchangeProperties;
import uk.dsx.ats.data.PriceProperties;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.*;

/**
 * @author Mikhail Wall
 */

@Log4j2
public class DSXUtils {

    private static final String CONFIG_FILE = "config.json";

    private static Config CONFIG = DSXUtils.getPropertiesFromConfig(CONFIG_FILE);
    public static PriceProperties PRICE_PROPERTIES = CONFIG.getPriceProperties();

    public static Exchange createExchange() throws IOException {

        ExchangeSpecification exSpec = new ExchangeSpecification(DSXExchange.class);

        ExchangeProperties properties = CONFIG.getExchangeProperties();

        if (properties != null) {
            if (properties.getSecretKey() != null && properties.getApiKey() != null) {
                exSpec.setSecretKey(properties.getSecretKey());
                exSpec.setApiKey(properties.getApiKey());
            } else
                throw new IOException("Cannot get config for api keys");
        } else {
            throw new IOException("Cannot get config for api keys");
        }

        exSpec.setSslUri(properties.getUrl());
        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(exSpec);
        exchange.remoteInit();

        return exchange;
    }

    private static Config getPropertiesFromConfig(String config) {
        return getClassFromProperties(config, Config.class);
    }

    private static <T> T getClassFromProperties(String config, Class<T> klass) {
        ObjectMapper mapper = new ObjectMapper();
        T returnValue = null;
        try {
            returnValue = mapper.readValue(new File(config), klass);
        } catch (IOException e) {
            logErrorWithException("Cannot get properties", e);
        }
        return returnValue;
    }

    public static int getRateLimitFromProperties(String config, String exchangeName) {

        JsonObject jsonObject = getJsonObject(config);
        if (jsonObject != null)
            return jsonObject.getInt(exchangeName);

        // default value for rateLimit, if we cannot get it from properties
        return 5000;
    }

    private static JsonObject getJsonObject(String config) {
        try (
                InputStream input = new FileInputStream(config);
                JsonReader jsonReader = Json.createReader(input)
        ){
            return jsonReader.readObject();

        } catch (FileNotFoundException e) {
            logErrorWithException("File not found", e);
        } catch (IOException e) {
            logErrorWithException("Cannot close input stream for config file", e);
        }

        return null;
    }

    public static void logInfo(String message, Object... args) {
        AtsMain.logInfo.info(message, args);
        AtsMain.logAudit.info(message, args);
    }

    public static void logErrorWithException(String message, Exception e, Object... args) {
        AtsMain.logInfo.error(message, args, e.getMessage(), e);
        AtsMain.logAudit.error(message, args, e.getMessage());
    }

    public static void logError(String message, Object... args) {
        AtsMain.logInfo.error(message, args);
        AtsMain.logAudit.error(message, args);
    }
}
