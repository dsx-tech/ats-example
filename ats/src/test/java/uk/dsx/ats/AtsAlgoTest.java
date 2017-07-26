package uk.dsx.ats;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dsx.dto.trade.DSXCancelOrderResult;
import org.knowm.xchange.dsx.dto.trade.DSXOrderStatusResult;
import org.knowm.xchange.dsx.service.DSXTradeServiceRaw;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.service.trade.TradeService;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import uk.dsx.ats.data.AlgorithmArgs;
import uk.dsx.ats.data.ExchangeData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mock;
import static uk.dsx.ats.AtsMain.*;
import static uk.dsx.ats.data.PriceProperties.DEFAULT_PRICE_ADDITION;
import static uk.dsx.ats.data.PriceProperties.DEFAULT_VOLUME_SCALE;
import static uk.dsx.ats.utils.DSXUtils.PRICE_PROPERTIES;

/**
 * @author Mikhail Wall
 */

@RunWith(PowerMockRunner.class)
@PowerMockIgnore("javax.management.*")
public class AtsAlgoTest {

    private static final Logger logInfo = LogManager.getLogger("info-log");
    private static final Logger logAudit = LogManager.getLogger("audit-log");

    Exchange exchangeMock;
    Exchange exchangeMockAfterTrade;
    TradeService tradeService;
    DSXTradeServiceRaw dsxTradeServiceRaw;

    Date date;
    AlgorithmArgs algorithmArgs;
    Algorithm algorithm;

    ExchangeData exchangeData;

    @Before
    public void prepare() {
        exchangeMock = mock(Exchange.class);
        exchangeMockAfterTrade = mock(Exchange.class);
        tradeService = mock(TradeService.class);
        dsxTradeServiceRaw = mock(DSXTradeServiceRaw.class);

        PRICE_PROPERTIES.setAveragePriceUpdateTime(10000L);
        date = new Date();

        algorithmArgs = new AlgorithmArgs(PRICE_PROPERTIES, exchangeMock, tradeService, dsxTradeServiceRaw);
        algorithm = new Algorithm(algorithmArgs, logInfo, logAudit, date);

        PowerMockito.mockStatic(AtsMain.class);

        exchangeData = new ExchangeData(exchangeMock);
        exchangeData.setPrice(new BigDecimal("2500"));
        exchangeData.setTimestamp(System.currentTimeMillis() / 1000);
        AVERAGE_PRICE.setExchanges(new ArrayList<>(Collections.singletonList(exchangeData)));
    }

    @PrepareForTest({AtsMain.class})
    @Test(timeout = 20000)
    public void testAlgoExec() throws Exception {
        PowerMockito.when(getBidOrderHighestPrice(exchangeMock)).thenReturn(new BigDecimal("2500"));
        PowerMockito.when(getBidOrderHighestPriceDSX(exchangeMock)).thenReturn(new BigDecimal("2400"));

        PowerMockito.when(dsxTradeServiceRaw.cancelDSXOrder(1L)).thenReturn(new DSXCancelOrderResult(null, 1L));
        PowerMockito.when(dsxTradeServiceRaw.cancelDSXOrder(2L)).thenReturn(new DSXCancelOrderResult(null, 2L));
        PowerMockito.when(getFunds(exchangeMock)).thenReturn(new Balance(Currency.USD,
                new BigDecimal("20000"), new BigDecimal("20000")));

        BigDecimal dsxPriceMock = getBidOrderHighestPriceDSX(exchangeMock);
        BigDecimal dsxPriceWithAdditionMock = dsxPriceMock.add(DEFAULT_PRICE_ADDITION);
        BigDecimal volumeMock = getFunds(exchangeMock).getAvailable().divide(dsxPriceWithAdditionMock, DEFAULT_VOLUME_SCALE,
                RoundingMode.DOWN);

        PowerMockito.when(tradeService.placeLimitOrder(new LimitOrder(Order.OrderType.BID, volumeMock,
                CURRENCY_PAIR, "", date, dsxPriceWithAdditionMock))).thenReturn("1");

        PowerMockito.when(algorithmArgs.getDsxTradeServiceRaw().getOrderStatus(1L)).thenReturn(
                new DSXOrderStatusResult("btcusd", "ask", new BigDecimal("10000"), new BigDecimal("20000"),
                        new BigDecimal("2400.01"), date.getTime(), 1, "type", null));

        PowerMockito.when(algorithmArgs.getDsxTradeServiceRaw().getOrderStatus(2L)).thenReturn(new DSXOrderStatusResult("btcusd",
                "ask", new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("2400.01"),
                date.getTime(), 0, "type", null));

        PowerMockito.when(getVolumeBeforeOrder(exchangeMock, dsxPriceWithAdditionMock)).thenReturn(new BigDecimal("2401"));

        PowerMockito.when(getBidOrderHighestPriceDSX(exchangeMockAfterTrade)).thenReturn(new BigDecimal("2401"));

        boolean isAlgorithmEnded = false;
        while (!isAlgorithmEnded) {
            isAlgorithmEnded = algorithm.executeAlgorithm();
        }

        verify(tradeService, times(0)).cancelOrder("2");
        verify(tradeService, times(0)).cancelOrder("1");
        verify(tradeService, times(1)).placeLimitOrder(new LimitOrder(Order.OrderType.BID, volumeMock,
                CURRENCY_PAIR, "", date, dsxPriceWithAdditionMock));
    }

    @PrepareForTest({AtsMain.class})
    @Test(timeout = 20000)
    public void testAlgoExecWithCancel() throws Exception {
        PowerMockito.when(getBidOrderHighestPrice(exchangeMock)).thenReturn(new BigDecimal("2500"));
        PowerMockito.when(getBidOrderHighestPriceDSX(exchangeMock)).thenReturn(new BigDecimal("2410"));

        PowerMockito.when(dsxTradeServiceRaw.cancelDSXOrder(2L)).thenReturn(new DSXCancelOrderResult(null, 2L));
        PowerMockito.when(getFunds(exchangeMock)).thenReturn(new Balance(Currency.USD,
                new BigDecimal("20000"), new BigDecimal("20000")));

        BigDecimal dsxPriceMock = getBidOrderHighestPriceDSX(exchangeMock);
        BigDecimal dsxPriceWithAdditionMock = dsxPriceMock.add(DEFAULT_PRICE_ADDITION);
        BigDecimal volumeMock = getFunds(exchangeMock).getAvailable().divide(dsxPriceWithAdditionMock, DEFAULT_VOLUME_SCALE,
                RoundingMode.DOWN);

        PowerMockito.when(tradeService.placeLimitOrder(new LimitOrder(Order.OrderType.BID, volumeMock,
                CURRENCY_PAIR, "", date, dsxPriceWithAdditionMock))).thenReturn("2");

        PowerMockito.when(algorithmArgs.getDsxTradeServiceRaw().getOrderStatus(2L)).thenReturn(new DSXOrderStatusResult("btcusd",
                "ask", new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("2400.01"),
                date.getTime(), 0, "type", null),
                new DSXOrderStatusResult("btcusd",
                        "ask", new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("2400.01"),
                        date.getTime(), 0, "type", null),
                new DSXOrderStatusResult("btcusd",
                        "ask", new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("2400.01"),
                        date.getTime(), 1, "type", null));

        PowerMockito.when(getVolumeBeforeOrder(exchangeMock, dsxPriceWithAdditionMock)).thenReturn(new BigDecimal("2401"));

        PowerMockito.when(getBidOrderHighestPriceDSX(exchangeMockAfterTrade)).thenReturn(new BigDecimal("2401"));

        boolean isAlgorithmEnded = false;
        while (!isAlgorithmEnded) {
            isAlgorithmEnded = algorithm.executeAlgorithm();
        }

        verify(tradeService, times(1)).cancelOrder("2");
        verify(tradeService, times(2)).placeLimitOrder(new LimitOrder(Order.OrderType.BID, volumeMock,
                CURRENCY_PAIR, "", date, dsxPriceWithAdditionMock));

    }
}
