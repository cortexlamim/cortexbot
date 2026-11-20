const { analyzeCandles } = require("./indicators");

function generateSignal(candles) {
  if (!Array.isArray(candles) || candles.length < 50) {
    throw new Error("At least 50 candles are required");
  }

  const indicators = analyzeCandles(candles);
  const close = candles.at(-1).close;
  const previous = candles.at(-2).close;

  const { EMA9, EMA21, EMA50, RSI, MACD, Bollinger, ATR } = indicators;

  let score = 0;
  let bullish = 0;
  let bearish = 0;
  const reasons = [];

  if (
    !Number.isFinite(EMA9) ||
    !Number.isFinite(EMA21) ||
    !Number.isFinite(EMA50) ||
    !Number.isFinite(RSI) ||
    !MACD || !Number.isFinite(MACD.histogram) ||
    !Bollinger || !Number.isFinite(ATR)
  ) {
    return {
      signal: "NO TRADE",
      score: 0,
      strength: 0,
      scoreMeaning: "Analysis strength, not win probability",
      reasons: ["Indicator data incomplete"]
    };
  }

  // 1. EMA trend
  if (EMA9 > EMA21 && EMA21 > EMA50) {
    score += 25;
    bullish++;
    reasons.push("Bullish EMA alignment");
  } else if (EMA9 < EMA21 && EMA21 < EMA50) {
    score -= 25;
    bearish++;
    reasons.push("Bearish EMA alignment");
  } else {
    reasons.push("Mixed EMA trend");
  }

  // 2. MACD momentum
  if (MACD.histogram > 0) {
    score += 20;
    bullish++;
    reasons.push("Positive MACD momentum");
  } else if (MACD.histogram < 0) {
    score -= 20;
    bearish++;
    reasons.push("Negative MACD momentum");
  }

  // 3. RSI
  if (RSI >= 52 && RSI <= 68) {
    score += 15;
    bullish++;
    reasons.push("Bullish RSI");
  } else if (RSI >= 32 && RSI <= 48) {
    score -= 15;
    bearish++;
    reasons.push("Bearish RSI");
  } else {
    reasons.push("RSI neutral or extreme");
  }

  // 4. Bollinger position
  if (close > Bollinger.middle && close < Bollinger.upper) {
    score += 10;
    bullish++;
    reasons.push("Price above Bollinger middle");
  } else if (close < Bollinger.middle && close > Bollinger.lower) {
    score -= 10;
    bearish++;
    reasons.push("Price below Bollinger middle");
  }

  // 5. Price vs EMA21
  if (close > EMA21) {
    score += 15;
    bullish++;
    reasons.push("Price above EMA21");
  } else if (close < EMA21) {
    score -= 15;
    bearish++;
    reasons.push("Price below EMA21");
  }

  // 6. Recent momentum
  const recent = candles.slice(-4);
  const rising = recent.every((c, i) =>
    i === 0 || c.close > recent[i - 1].close
  );
  const falling = recent.every((c, i) =>
    i === 0 || c.close < recent[i - 1].close
  );

  if (rising) {
    score += 15;
    bullish++;
    reasons.push("Bullish candle momentum");
  } else if (falling) {
    score -= 15;
    bearish++;
    reasons.push("Bearish candle momentum");
  }

  // 7. Sideways market filter
  const emaGap = Math.abs(EMA9 - EMA21);
  const atrRatio = ATR / close;

  const sideways =
    emaGap < ATR * 0.08 &&
    atrRatio < 0.0005;

  if (sideways) {
    reasons.push("Sideways market detected");
  }

  // 8. Final decision
  let signal = "NO TRADE";

  if (!sideways && score >= 60 && bullish >= 4) {
    signal = "CALL";
  } else if (!sideways && score <= -60 && bearish >= 4) {
    signal = "PUT";
  }

  return {
    signal,
    score,
    strength: Math.abs(score),
    scoreMeaning: "Analysis strength, not win probability",
    confirmations: {
      bullish,
      bearish,
      required: 4
    },
    marketCondition: sideways ? "SIDEWAYS" : "TRENDING",
    indicators,
    reasons,
    marketPrice: close,
    atr: ATR,
    timestamp: candles.at(-1).time || null
  };
}

module.exports = { generateSignal };