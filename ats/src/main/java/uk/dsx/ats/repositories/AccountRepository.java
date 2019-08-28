package uk.dsx.ats.repositories;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.service.account.AccountService;
import uk.dsx.ats.utils.DSXUtils;

public class AccountRepository {

    private final AccountService accountService;
    private final Currency currency;

    public AccountRepository(AccountService accountService, Currency currency) {
        this.accountService = accountService;
        this.currency = currency;
    }

    public Balance getBalance() throws Exception {
        return DSXUtils.unlimitedRepeatableRequest("getFunds",
                () -> accountService.getAccountInfo().getWallet().getBalance(currency));
    }
}
