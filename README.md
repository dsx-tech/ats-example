# Automated Trading System example

DSX ATS example is using XChange library for communication with cryptocurrency exchanges. It takes BTC price from Bitfinex, 
Bitstamp, Kraken, and compare it with DSX price. If it satisfies pre-defined conditions, then ATS places an order to buy BTC 
on DSX. ATS moves active orders on price movements to garantee the best execution.

ATS supports cross instrument trading (only for fiat currencies), in this case cross currency rate are received
from [fixer.io](http://fixer.io) website.

#### How to use ATS
#### Ubuntu
For using this ATS you can run sh scripts. 
1) You have to give permissions to scripts. Run in CLI:  

    1) chmod +x buildAts.sh
    2) chmod +x startAts.sh
    3) chmod +x shutdownAts.sh
2) Run in CLI: ./buildAts.sh. This script will create jar file.
3) If you want to launch ATS, then run in CLI: ./startAts.sh.
4) If you want to stop ATS, then run in CLI: ./shutdownAts.sh.

#### Windows
Run startAts.bat script in command line

#### Config and rateLimit json files usage
Files config.json and rateLimit.json in ats-example are used for AtsMain class.
#### Clarification of config.json
        "url" : dsx.uk api address. Can be changed, that's why it's in properties.
        
        "secretKey": your account secret key,
    
        "apiKey": your account public key,
  
        "exchangesCurrencyPair": Currency pair to check on other exchanges, e.g. "BTC/USD",
        
        "dsxCurrencyPair": Currency pair to trade on DSX, e.g. "BTC/EUR",
        
        "minOrderSize": minimum order size, e.g. "0.001",
        
        "pricePercentage": percentage, for which multiply dsx price bid order for condition checking. 
        For example - "1.01" will check if bid order average bigger than dsx bid order * 1.01,
  
        "fxPercentage": percentage, for which multiply fx rates. e.g. "1.01",
  
        "priceScale": number of decimals after dot for price, e.g. 5,
  
        "volumeScale":  number of decimals after dot for volume, e.g. 4,
  
        "priceAddition": addition for order price, for example "0.01" - 1 cent,
  
        "averagePriceUpdateTime": how often to update average price in milliseconds, e.g. - 3000
  
        "timestampForPriceUpdate": timestamp in seconds for condition if current price timestamp less 
        than X seconds, than don't use this price for calculating average, e.g. - 3
  
        "dsxAccountType": type of dsx account, e.g. - "LIVE" or "DEMO"
  
        "stepToMove": step for order movement. If price in orderbook bigger than that step, cancel order 
        and place order with new price, e.g. - "0.01"
  
        "volumeToMove": "0.05" volume for order movement. If volume before user's order in order book 
        bigger than volumeToMove, cancel order and place order with new price
        
        "waitingTimeForOrderCheck": time for checking order status after it was placed. (in seconds)
        
        "waitingTimeForCheckingAccountFunds": time for checking if account have enough funds for placing order in seconds.
        
        "sensitivity": if difference betwenn price for order after user's order and user's order is bigger than that amount
         then replace order with updated price, e.g. - 5 (5 usd)
         
        "pmax": max price. If price is bid on dsx.uk is bigger than max price, then don't place order        
#### Clarification of rateLimit.json
        
        "Exchange" - time in seconds to set how often price from exchange should be taken
