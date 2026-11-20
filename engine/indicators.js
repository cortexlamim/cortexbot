function emaSeries(values, period) {
  const result = Array(values.length).fill(null);
  if (values.length < period) return result;

  let sum = 0;
  for (let i = 0; i < period; i++) sum += values[i];

  let previous = sum / period;
  result[period - 1] = previous;

  const k = 2 / (period + 1);

  for (let i = period; i < values.length; i++) {
    previous = values[i] * k + previous * (1 - k);
    result[i] = previous;
  }

  return result;
}

function calculateRSI(values, period = 14) {
  if (values.length <= period) return null;

  let gain = 0;
  let loss = 0;

  for (let i = 1; i <= period; i++) {
    const change = values[i] - values[i - 1];
    gain += Math.max(change, 0);
    loss += Math.max(-change, 0);
  }

  let avgGain = gain / period;
  let avgLoss = loss / period;

  for (let i = period + 1; i < values.length; i++) {
    const change = values[i] - values[i - 1];

    avgGain = (avgGain * (period - 1) +
      Math.max(change, 0)) / period;

    avgLoss = (avgLoss * (period - 1) +
      Math.max(-change, 0)) / period;
  }

  if (avgLoss === 0) return avgGain === 0 ? 50 : 100;

  const rs = avgGain / avgLoss;
  return 100 - 100 / (1 + rs);
}

function calculateMACD(values) {
  const fast = emaSeries(values, 12);
  const slow = emaSeries(values, 26);

  const macd = values.map((_, i) =>
    fast[i] === null || slow[i] === null
      ? null
      : fast[i] - slow[i]
  );

  const valid = macd.filter(v => v !== null);
  const signalValues = emaSeries(valid, 9);
  const signal = signalValues[signalValues.length - 1];

  const line = macd[macd.length - 1];

  return {
    macd: line,
    signal,
    histogram: line !== null && signal !== null
      ? line - signal
      : null
  };
}

function calculateBollinger(values, period = 20) {
  if (values.length < period) return null;

  const sample = values.slice(-period);
  const middle = sample.reduce((a, b) => a + b, 0) / period;

  const variance = sample.reduce(
    (sum, value) => sum + Math.pow(value - middle, 2), 0
  ) / period;

  const deviation = Math.sqrt(variance);

  return {
    upper: middle + 2 * deviation,
    middle,
    lower: middle - 2 * deviation
  };
}

function calculateATR(candles, period = 14) {
  if (candles.length <= period) return null;

  const ranges = [];

  for (let i = 1; i < candles.length; i++) {
    const current = candles[i];
    const previous = candles[i - 1];

    ranges.push(Math.max(
      current.high - current.low,
      Math.abs(current.high - previous.close),
      Math.abs(current.low - previous.close)
    ));
  }

  let atr = ranges.slice(0, period)
    .reduce((a, b) => a + b, 0) / period;

  for (let i = period; i < ranges.length; i++) {
    atr = (atr * (period - 1) + ranges[i]) / period;
  }

  return atr;
}

function analyzeCandles(candles) {
  if (!Array.isArray(candles) || candles.length < 35) {
    throw new Error("At least 35 candles are required");
  }

  const prices = candles.map(c => c.close);

  if (prices.some(v => !Number.isFinite(v)) ||
      candles.some(c =>
        !Number.isFinite(c.high) ||
        !Number.isFinite(c.low) ||
        c.high < c.low
      )) {
    throw new Error("Invalid candle data");
  }

  const ema9 = emaSeries(prices, 9).at(-1);
  const ema21 = emaSeries(prices, 21).at(-1);
  const ema50 = emaSeries(prices, 50).at(-1);
  const rsi = calculateRSI(prices);
  const macd = calculateMACD(prices);
  const bollinger = calculateBollinger(prices);
  const atr = calculateATR(candles);

  return {
    EMA9: ema9,
    EMA21: ema21,
    EMA50: ema50,
    RSI: rsi,
    MACD: macd,
    Bollinger: bollinger,
    ATR: atr
  };
}

module.exports = {
  emaSeries,
  calculateRSI,
  calculateMACD,
  calculateBollinger,
  calculateATR,
  analyzeCandles
};
