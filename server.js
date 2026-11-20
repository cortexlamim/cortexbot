require("dotenv").config();

const express = require("express");
const app = express();
const { generateSignal } = require("./engine/signals");

app.use(express.static("public"));

app.get("/api/market", async (req, res) => {
  const symbol = req.query.symbol || "EUR/USD";
  const interval = req.query.interval || "1min";
  const key = process.env.TWELVE_DATA_API_KEY;

  if (!key) {
    return res.status(503).json({
      status: "NO DATA",
      message: "API key is missing"
    });
  }

  try {
    const url = new URL(
      "https://api.twelvedata.com/time_series"
    );

    url.search = new URLSearchParams({
      symbol,
      interval,
      outputsize: "100",
      apikey: key
    });

const response = await fetch(url, {
  signal: AbortSignal.timeout(15000)
});
    const data = await response.json();

    if (!response.ok || !data.values) {
      return res.status(502).json({
        status: "NO DATA",
        message: data.message || "Market data unavailable"
      });
    }

    const candles = data.values.reverse().map(c => ({
      time: c.datetime,
      open: Number(c.open),
      high: Number(c.high),
      low: Number(c.low),
      close: Number(c.close)
    }));

    const prices = candles.map(c => c.close);

    function calculateEMA(values, period) {
      const k = 2 / (period + 1);
      let result = values[0];

      for (let i = 1; i < values.length; i++) {
        result = values[i] * k + result * (1 - k);
      }

      return result;
    }

    function calculateRSI(values, period = 14) {
      if (values.length <= period) return null;

      let gain = 0;
      let loss = 0;

      for (let i = values.length - period; i < values.length; i++) {
        const change = values[i] - values[i - 1];

        if (change > 0) gain += change;
        else loss -= change;
      }

      if (loss === 0) return 100;

      return 100 - (100 / (1 + gain / loss));
    }

    const ema9 = calculateEMA(prices, 9);
    const ema21 = calculateEMA(prices, 21);
    const rsi = calculateRSI(prices);

    let signal = "NO TRADE";

    if (rsi !== null) {
      if (ema9 > ema21 && rsi >= 52 && rsi <= 70) {
        signal = "UP";
      } else if (ema9 < ema21 && rsi >= 30 && rsi <= 48) {
        signal = "DOWN";
      }
    }

    res.json({
      status: "LIVE DATA",
      market: "REAL",
      pair: symbol,
      interval,
      latestCandle: candles[candles.length - 1].time,
      price: prices[prices.length - 1],
      indicators: {
        RSI: rsi === null ? null : Number(rsi.toFixed(2)),
        EMA9: Number(ema9.toFixed(6)),
        EMA21: Number(ema21.toFixed(6))
      },
      signal,
      candles
    });

  } catch (error) {
    res.status(500).json({
      status: "ERROR",
      message: "Market data connection failed"
    });
  }
});

app.get("/api/otc", (req, res) => {
  res.status(503).json({
    status: "OTC DATA UNAVAILABLE",
    message: "Verified OTC live data provider is not connected."
  });
});


app.get("/api/analyze", async (req, res) => {
  const symbol = req.query.symbol || "EUR/USD";
  const interval = req.query.interval || "1min";
  const key = process.env.TWELVE_DATA_API_KEY;

  if (!key) {
    return res.status(503).json({
      status: "NO DATA",
      message: "API key is missing"
    });
  }

  try {
    const url = new URL(
      "https://api.twelvedata.com/time_series"
    );

    url.search = new URLSearchParams({
      symbol,
      interval,
      outputsize: "100",
      apikey: key
    });

    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    const data = await response.json();

    if (!response.ok || !data.values) {
      return res.status(502).json({
        status: "NO DATA",
        message: data.message || "Market data unavailable"
      });
    }

    const candles = data.values.reverse().map(c => ({
      time: c.datetime,
      open: Number(c.open),
      high: Number(c.high),
      low: Number(c.low),
      close: Number(c.close)
    }));

    if (candles.length < 50) {
      return res.status(502).json({
        status: "INSUFFICIENT DATA",
        message: "At least 50 candles are required"
      });
    }

    const analysis = generateSignal(candles);

    res.json({
      status: "LIVE DATA",
      market: "REAL",
      pair: symbol,
      interval,
      ...analysis,
      candles
    });

  } catch (error) {
    console.error("Analysis error:", error.message);

    res.status(500).json({
      status: "ERROR",
      message: "Analysis failed"
    });
  }
});

app.listen(process.env.PORT || 3000, "0.0.0.0", () => {
  console.log("Cortex Bot running at http://localhost:3000");
});

