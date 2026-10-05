// Automatic retry/fallback for temporary Gemini generateContent overloads.
// This keeps the media pipeline resilient when the preferred Flash model returns 429/503/high-demand.
const originalFetch = global.fetch.bind(global);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function urlString(input) {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  if (input && typeof input.url === 'string') return input.url;
  return '';
}

function isGeminiGenerateContent(url) {
  return /^https:\/\/generativelanguage\.googleapis\.com\/v1beta\/models\/[^/:]+:generateContent(?:\?|$)/i.test(url);
}

async function overloadResponse(response) {
  if (!response || response.ok) return false;
  if (response.status === 429 || response.status === 503) return true;
  try {
    const text = await response.clone().text();
    return /high demand|overload|resource exhausted|temporarily unavailable|try again later/i.test(text);
  } catch {
    return false;
  }
}

function replaceModel(url, model) {
  return url.replace(/\/models\/[^/:]+:generateContent/i, `/models/${encodeURIComponent(model)}:generateContent`);
}

const fallbackModels = String(process.env.GEMINI_OVERLOAD_FALLBACK_MODELS || 'gemini-3.7-flash,gemini-3.5-flash')
  .split(',')
  .map(x => x.trim())
  .filter(Boolean);

global.fetch = async function resilientGeminiFetch(input, init) {
  const url = urlString(input);
  if (!isGeminiGenerateContent(url)) return originalFetch(input, init);

  let response = await originalFetch(input, init);
  if (!(await overloadResponse(response))) return response;

  console.warn(`[gemini-fallback] preferred model overloaded (${response.status}); retrying once`);
  await sleep(900);
  response = await originalFetch(input, init);
  if (!(await overloadResponse(response))) {
    console.log('[gemini-fallback] preferred model recovered on retry');
    return response;
  }

  for (const model of fallbackModels) {
    const fallbackUrl = replaceModel(url, model);
    console.warn(`[gemini-fallback] switching request to ${model}`);
    await sleep(300);
    const fallbackResponse = await originalFetch(fallbackUrl, init);
    if (!(await overloadResponse(fallbackResponse))) {
      console.log(`[gemini-fallback] ${model} accepted request status=${fallbackResponse.status}`);
      return fallbackResponse;
    }
    response = fallbackResponse;
  }

  console.error('[gemini-fallback] all configured models were overloaded');
  return response;
};

console.log(`Gemini overload fallback active: ${fallbackModels.join(' -> ')}`);
