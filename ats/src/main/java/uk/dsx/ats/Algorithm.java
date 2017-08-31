package uk.dsx.ats;

import lombok.Value;
import org.apache.logging.log4j.Logger;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeService;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import uk.dsx.ats.data.AlgorithmArgs;
import uk.dsx.ats.data.PriceProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static uk.dsx.ats.AtsMain.*;
import static uk.dsx.ats.utils.DSXUtils.logInfo;
import static uk.dsx.ats.utils.DSXUtils.logError;
import static uk.dsx.ats.utils.DSXUtils.logErrorWithException;

/**
 * @author Mikhail Wall
 */

@Value
public class Algorithm {

    private static final int REQUEST_TO_DSX_TIMEOUT_SECONDS = 10;
    private static final BigDecimal LOW_VOLUME = new BigDecimal("0.1");

    AlgorithmArgs args;
    Logger logInfo;
    Logger logAudit;
    Date date;

    public Algorithm(AlgorithmArgs args, Logger logInfo, Logger logAudit, Date date) {
        this.args = args;
        this.logInfo = logInfo;
        this.logAudit = logAudit;
        this.date = date;
    }

    @FunctionalInterface
    public interface ConnectorRequest<T> {
        T get() throws Exception;
    }

    private void sleep(String interruptedMessage) {
        try {
            TimeUnit.SECONDS.sleep(REQUEST_TO_DSX_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            if (interruptedMessage != null)
                logInfo.warn(interruptedMessage);
        }
    }

    private <T> T unlimitedRepeatableRequest(String methodName, ConnectorRequest<T> requestObject) throws Exception {
        while (!Thread.interrupted()) {
            try {
                return requestObject.get();
            } catch (Exception e) {
                if (e.getMessage().contains("418")) {
                    logErrorWithException("Cannot connect to dsx.uk, waiting 1 sec to try again", e);
                    sleep(String.format("%s interrupted", methodName));
                } else
                    throw e;
            }
        }
        throw new InterruptedException(String.format("%s interrupted", methodName));
    }

    public void cancelAllOrders(DSXTradeService tradeService) throws Exception {

        // set limit order return value for placing new order
        args.setLimitOrderReturnValue(null);
        args.setOrderId(0L);
        // setting average price to null for updating dsx price, when cancelling order
        args.setAveragePrice(null);

        unlimitedRepeatableRequest("cancelAllOrders", tradeService::cancelAllOrders);
        logInfo("Cancelled all orders order");
    }

    public boolean executeAlgorithm() throws Exception {
        PriceProperties priceConstants = args.getPriceProperties();

        //waiting for our price to be better than average price on supported exchanges
        awaitGoodPrice();

        //calculating data for placing order on our exchange
        BigDecimal dsxPriceWithAddition = args.getDsxPrice().add(priceConstants.getPriceAddition());

        BigDecimal volume = unlimitedRepeatableRequest("getFunds", () ->
                getFunds(args.getDsxExchange()).getAvailable().divide(dsxPriceWithAddition, priceConstants.getVolumeScale(),
                        RoundingMode.DOWN));

        // condition for not printing volume, when this is redundant
        if (volume.compareTo(LOW_VOLUME) == 1) {
            logInfo("Buying volume: {}", volume);
            unlimitedRepeatableRequest("cancelAllOrders", () ->
                    args.getDsxTradeServiceRaw().cancelAllDSXOrders());
            logInfo("Cancelled all previous orders in case there was placed order");
        }
        try {
            //check if we have enough money to place order
            if (volume.compareTo(LOW_VOLUME) == -1) {
                //we don't have enough money to place order
                //if we have previously placed order we should check it's status
                if (args.getOrderId() != 0L) {
                    // if order status is filled - algorithm executed successfully
                    int orderStatus = unlimitedRepeatableRequest("getOrderStatus", () ->
                            args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()).getStatus());

                    if (orderStatus == 1) {
                        logInfo("Order status is filled");
                        return true;
                    }
                } else {
                    //if we don't have any order it means there is not enough money on our account. Algorithm stopping.
                    logError("Couldn't place order. Not enough money.");
                    return true;
                }
            }
            if (args.getLimitOrderReturnValue() == null) {
                //place new order
                String limitOrderReturnValue = unlimitedRepeatableRequest("placeLimitOrder", () ->
                        args.getTradeService().placeLimitOrder(new LimitOrder(Order.OrderType.BID, volume, CURRENCY_PAIR,
                                "", date, dsxPriceWithAddition)));
                logInfo("Order with id {} was placed", limitOrderReturnValue);
                args.setLimitOrderReturnValue(limitOrderReturnValue);
            }

            args.setOrderId(Long.parseLong(args.getLimitOrderReturnValue()));

            //sleep some time before checking order status
            TimeUnit.SECONDS.sleep(priceConstants.getWaitingTimeForOrderCheck());

            //get actual order status
            DSXOrderStatusResult result = unlimitedRepeatableRequest("getOrderStatus", () ->
                    args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()));
            logInfo("Actual order status: {}", result.getStatus());

            //get current DSX price
            BigDecimal dsxCurrentPrice = unlimitedRepeatableRequest("getBidOrderHighestPriceDSX", () ->
                    getBidOrderHighestPriceDSX(args.getDsxExchange()));

            // Order status == Filled - algorithm executed correctly
            if (result.getStatus() == 1) {
                logInfo("Order was filled");
                return true;
            }

            // if order status not filled - check that order is actual (top bid or so and price is good).
            BigDecimal volumeBeforeOrder = unlimitedRepeatableRequest("getVolumeBeforeVolume", () ->
                    getVolumeBeforeOrder(args.getDsxExchange(), dsxPriceWithAddition));

            if (result.getRate().subtract(dsxCurrentPrice).abs().compareTo(priceConstants.getStepToMove()) > 0
                    || volumeBeforeOrder.compareTo(priceConstants.getVolumeToMove()) >= 0) {
                //if price is good and order needs to be replaced (with better price). check order status again
                long orderStatus = unlimitedRepeatableRequest("getOrderStatus", () ->
                        args.getDsxTradeServiceRaw().getOrderStatus(args.getOrderId()).getStatus());
                logInfo("Actual order status: {}", orderStatus);
                // if order status is not filled or killed, then cancel order so we can place another order
                if (orderStatus != 1 && orderStatus != 2) {
                    unlimitedRepeatableRequest("cancelOrder", () ->
                            args.getTradeService().cancelOrder(args.getLimitOrderReturnValue()));
                    logInfo("Cancelling order, because better price exists");
                    args.setOrderId(0L);
                    args.setAveragePrice(null);
                }
                args.setLimitOrderReturnValue(null);
            }
        } catch (ExchangeException e) {
            logErrorWithException("Exchange exception: {}", e);
        }
        return false;
    }


    private void awaitGoodPrice() throws Exception {
        PriceProperties priceProperties = args.getPriceProperties();

        while (args.getAveragePrice() == null || !isDSXPriceGood(priceProperties)) {
            //get new average price from other exchanges
            args.setAveragePrice(AVERAGE_PRICE.getAveragePrice(priceProperties.getTimestampForPriceUpdate(),
                    priceProperties.getPriceScale()));

            //get DSX price
            args.setDsxPrice(unlimitedRepeatableRequest("getBidOrderHighestPriceDSX", () ->
                    getBidOrderHighestPriceDSX(args.getDsxExchange())));

            if (args.getAveragePrice() != null) {
                logInfo("Average price: {}, dsxPrice: {}", args.getAveragePrice(), args.getDsxPrice());
                //if DSX price is bad
                if (!isDSXPriceGood(priceProperties)) {
                    //if we have previously placed order - we should kill it or it can be filled by bad price.
                    if (args.getOrderId() != 0L) {
                        try {
                            unlimitedRepeatableRequest("cancelOrder", () ->
                                    args.getTradeService().cancelOrder(args.getLimitOrderReturnValue()));
                            logInfo("Previous order was cancelled, because it had bad price");
                        } catch (ExchangeException e) {
                            logError("Order was already filled or killed.");
                            return;
                        }
                        args.setOrderId(0L);
                    }
                    logInfo("Cannot execute order. Waiting for price changing...");
                    //if DSX price is bad - waiting
                    TimeUnit.MILLISECONDS.sleep(priceProperties.getAveragePriceUpdateTime());
                }
            } else {
                // if we cannot get price from any exchange than cancel order, bcs average price can become better
                unlimitedRepeatableRequest("cancelAllOrders", () ->
                        args.getDsxTradeServiceRaw().cancelAllDSXOrders());
                logInfo("Waiting for connection to exchanges");
                sleep("Sleep was interrupted");
            }
        }
    }

    private boolean isDSXPriceGood(PriceProperties priceProperties) {

        args.setAveragePrice(AVERAGE_PRICE.getAveragePrice(priceProperties.getTimestampForPriceUpdate(),
                priceProperties.getPriceScale()));
        BigDecimal averagePrice = args.getAveragePrice();
        BigDecimal dsxPrice = args.getDsxPrice();
        logInfo("Average price: {}, dsxPrice: {}", averagePrice, dsxPrice);
        return averagePrice != null && averagePrice.compareTo(dsxPrice.multiply(priceProperties.getPricePercentage())) >= 0;
    }
}
