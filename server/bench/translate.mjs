/**
 * **번역 겨루기.** 같은 시험지(`translate-cases.tsv` 한국어 121문장, `--sheet=b` 는 `translate-cases-b.tsv` 60문장; 각각 × 영·일·중)를 여러 모델·지시문에 돌려 점수를 낸다.
 *
 * ## 왜 필요한가
 *
 * "번역이 별로다" 를 느낌으로 고치면 안 된다. 모델을 바꾸거나 지시문을 손봤을 때 **정말 좋아졌는지**를 숫자로 봐야 한다.
 * 교정 쪽 `models.mjs` 가 같은 이유로 있다.
 *
 * ## 무엇을 재나
 *
 *   chrF   정답(사람이 쓴 번역)과 글자 n-gram(1~6)이 얼마나 겹치나. 100 이 같은 글. 번역은 정답이 하나가 아니라서
 *          절대값보다 **같은 시험지로 모델·지시문끼리 견주는 값**으로 읽어라. 일본어·중국어는 글자 단위라 잘 맞는다.
 *   글자   번역문이 그 언어 글자로 쓰였나(일본어는 가나, 중국어는 한자이고 가나 없음, 영어는 한글 없음). 실패 = 엉뚱한 언어.
 *   숫자   원문의 숫자가 번역에 그대로 있나(전화번호·금액·날짜가 틀리면 쓸 수 없는 번역이다).
 *   한글   번역에 한글이 남았나(못 옮긴 것).
 *
 * chrF 가 높아도 어색한 번역이 있다. **이 도구가 낸 글을 사람이 직접 읽어라** — `--show` 로 한국어·정답·번역을 나란히 찍는다.
 *
 * ## 쓰는 법
 *
 *   UPSTAGE_API_KEY=... node bench/translate.mjs solar-pro4 --prompt=v1 --prompt=v2
 *   ANTHROPIC_API_KEY=... node bench/translate.mjs claude-haiku-5-5 claude-sonnet-5-5 --prompt=v2 --langs=en,ja --limit=40
 *   node bench/translate.mjs --fake            키 없이 도구만 시험(정답을 조금 망쳐 돌려준다)
 *
 * **값이 든다.** 돌리기 전에 사람에게 값을 말하고 허락을 받아라(CLAUDE.md '돈'). `--maxWon` 이 상한이다(기본 300원) —
 * 넘으면 그 자리에서 멈춘다.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { translatePrompt, translatePromptV2, translatePromptV3, TRANSLATE_TARGETS } from '../src/openai.js';

const args = process.argv.slice(2);
const flag = (name, fallback = '') => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const FAKE = args.includes('--fake');
const SHOW = args.includes('--show');
const LANGS = flag('langs', 'en,ja,zh').split(',').filter(Boolean);
const LIMIT = Number(flag('limit', '0')) || Infinity;
const MAX_WON = Number(flag('maxWon', '300'));
const OUT = flag('out', '');
const CATS = flag('cats', '').split(',').filter(Boolean);
const PROMPTS = args.filter((a) => a.startsWith('--prompt=')).map((a) => a.slice('--prompt='.length));
if (!PROMPTS.length) PROMPTS.push('v2');
const models = args.filter((a) => !a.startsWith('--'));
if (FAKE && !models.length) models.push('fake');
if (!models.length) {
  console.error('모델 이름을 하나 이상 대라. 예: node bench/translate.mjs solar-pro4');
  process.exit(2);
}

/** `v1`, `v2`, `v3`, `v2-shots5`(보기 5개만), `v2-shots0`(보기 없음), 또는 지시문 파일 경로. */
const promptFor = (name, lang) => {
  if (name === 'v1') return translatePrompt(lang);
  const m = /^(v2|v3)(?:-shots(\d+))?$/.exec(name);
  if (m) {
    const options = m[2] == null ? {} : { shots: Number(m[2]) };
    return m[1] === 'v3' ? translatePromptV3(lang, options) : translatePromptV2(lang, options);
  }
  return readFileSync(name, 'utf8');
};

// --- 시험지 ----------------------------------------------------------------------------------------------
// `--sheet=a`(기본, 121문장) | `b`(60문장 — 지시문·보기에 없는 문장이다. 지시문을 시험지 A 의 실수로 고쳤으니 부풀지 않은 값은 여기서 본다) | `ab`.
const SHEETS = { a: 'translate-cases.tsv', b: 'translate-cases-b.tsv' };
const sheetKeys = [...flag('sheet', 'a')].filter((k) => SHEETS[k]);
const cases = sheetKeys
  .flatMap((k) => readFileSync(new URL(`./${SHEETS[k]}`, import.meta.url), 'utf8').split('\n').slice(1).filter(Boolean))
  .map((line) => {
    const [id, cat, ko, en, ja, zh] = line.split('\t');
    return { id, cat, ko, ref: { en, ja, zh } };
  })
  .filter((c) => !CATS.length || CATS.includes(c.cat))
  .slice(0, LIMIT);

// --- 값 --------------------------------------------------------------------------------------------------
/** 100만 토큰당 입력/출력 값($). 2026-10 조사값. **없는 모델은 값을 모르는 모델이다 — 모른다고 찍는다.** */
const PRICE = {
  'solar-pro4': [0.30, 1.20],
  'solar-pro3': [0.15, 0.60],
  'claude-haiku-5-5': [0.10, 0.50],
  'claude-haiku-4-5': [1.0, 5.0],
  'claude-sonnet-5-5': [2.0, 10.0],
  'gpt-5-mini': [0.25, 2.0],
  'gpt-5-nano': [0.05, 0.40],
  'gpt-4.1-mini': [0.40, 1.60],
};
const KRW = 1400;
let spentWon = 0;
let stopped = false;

// --- 모델 부르기 -------------------------------------------------------------------------------------------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function askUpstage(spec, system, text) {
  const [model, effort] = spec.split('@');
  const res = await fetch('https://api.upstage.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env.UPSTAGE_API_KEY}` },
    body: JSON.stringify({
      model,
      messages: [{ role: 'system', content: system }, { role: 'user', content: text }],
      temperature: 0,
      max_tokens: 1024,
      reasoning_effort: effort || (model.startsWith('solar-pro4') ? 'none' : 'minimal'),
    }),
  });
  if (!res.ok) return { error: `HTTP ${res.status} ${(await res.text()).slice(0, 120)}` };
  const body = await res.json();
  const used = body?.usage ?? {};
  return {
    text: (body?.choices?.[0]?.message?.content ?? '').trim(),
    tokens: { in: Number(used.prompt_tokens ?? 0), out: Number(used.completion_tokens ?? 0) },
  };
}

async function askClaude(spec, system, text) {
  const [model, variant] = spec.split('@');
  const five = /-5-5$|-5$/.test(model);
  const body = { model, max_tokens: 1024, system, messages: [{ role: 'user', content: text }] };
  if (five) {
    // 5 계열은 temperature 를 받지 않는다(400). 생각은 번역에 필요 없어 끈다 — haiku 는 disabled, sonnet 은 between_tools.
    body.thinking = model.includes('sonnet') ? { type: 'between_tools' } : { type: 'disabled' };
    body.output_config = { effort: variant || 'low' };
  } else {
    body.temperature = 0;
  }
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': process.env.ANTHROPIC_API_KEY,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) return { error: `HTTP ${res.status} ${(await res.text()).slice(0, 160)}` };
  const reply = await res.json();
  const used = reply?.usage ?? {};
  return {
    text: (reply?.content ?? []).filter((b) => b?.type === 'text').map((b) => b.text ?? '').join('').trim(),
    tokens: { in: Number(used.input_tokens ?? 0), out: Number(used.output_tokens ?? 0) },
  };
}

async function askOpenAi(spec, system, text) {
  const [model, effort] = spec.split('@');
  const body = { model, messages: [{ role: 'system', content: system }, { role: 'user', content: text }] };
  if (model.startsWith('gpt-5')) {
    body.reasoning_effort = effort || 'minimal';
    body.max_completion_tokens = 1024;
  } else {
    body.temperature = 0;
    body.max_tokens = 1024;
  }
  const res = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env.OPENAI_API_KEY}` },
    body: JSON.stringify(body),
  });
  if (!res.ok) return { error: `HTTP ${res.status} ${(await res.text()).slice(0, 120)}` };
  const reply = await res.json();
  const used = reply?.usage ?? {};
  return {
    text: (reply?.choices?.[0]?.message?.content ?? '').trim(),
    tokens: { in: Number(used.prompt_tokens ?? 0), out: Number(used.completion_tokens ?? 0) },
  };
}

/** 키 없이 도구만 시험: 정답의 끝을 조금 깎아 돌려준다. */
async function askFake(_spec, _system, text, caseRef) {
  return { text: (caseRef ?? text).slice(0, Math.max(1, (caseRef ?? text).length - 1)), tokens: { in: 500, out: 40 } };
}

const send = (spec, system, text, caseRef) =>
  FAKE ? askFake(spec, system, text, caseRef)
    : spec.startsWith('claude') ? askClaude(spec, system, text)
    : spec.startsWith('gpt') ? askOpenAi(spec, system, text)
    : askUpstage(spec, system, text);

/** 끊기면 다시 묻는다(429·5xx·연결 오류). 우리 잘못(4xx)이면 다시 물어도 같다. */
async function ask(spec, system, text, caseRef, tries = 4) {
  if (stopped) return { error: '상한을 넘겨 멈췄다' };
  for (let i = 0; i < tries; i++) {
    try {
      const started = Date.now();
      const got = await send(spec, system, text, caseRef);
      if (!got.error) {
        got.ms = Date.now() - started;
        const price = PRICE[spec.split('@')[0]];
        if (price && got.tokens) {
          spentWon += ((got.tokens.in * price[0] + got.tokens.out * price[1]) / 1e6) * KRW;
          if (spentWon > MAX_WON) {
            stopped = true;
            console.error(`\n상한 ${MAX_WON}원을 넘겼다(${spentWon.toFixed(1)}원). 남은 문항은 버린다.`);
          }
        }
        return got;
      }
      const code = Number(/HTTP (\d+)/.exec(got.error)?.[1] ?? 0);
      if (code !== 429 && code < 500) return got;
    } catch (e) {
      if (i === tries - 1) return { error: String(e?.cause?.code ?? e?.message ?? e) };
    }
    await sleep(500 * 2 ** i);
  }
  return { error: '여러 번 물어도 안 된다' };
}

// --- 점수 ------------------------------------------------------------------------------------------------
/** chrF(β=2): 글자 n-gram(1~6) 정밀도·재현율의 평균으로 낸 F 값. 공백은 버린다. 0~100. */
function chrf(hyp, ref) {
  const h = [...hyp.replace(/\s+/g, '')];
  const r = [...ref.replace(/\s+/g, '')];
  if (!h.length || !r.length) return 0;
  let ps = 0;
  let rs = 0;
  let used = 0;
  for (let n = 1; n <= 6; n++) {
    const count = (arr) => {
      const m = new Map();
      for (let i = 0; i + n <= arr.length; i++) {
        const k = arr.slice(i, i + n).join('');
        m.set(k, (m.get(k) ?? 0) + 1);
      }
      return m;
    };
    const hc = count(h);
    const rc = count(r);
    const th = [...hc.values()].reduce((a, b) => a + b, 0);
    const tr = [...rc.values()].reduce((a, b) => a + b, 0);
    if (!th || !tr) continue;
    let match = 0;
    for (const [k, v] of hc) match += Math.min(v, rc.get(k) ?? 0);
    ps += match / th;
    rs += match / tr;
    used++;
  }
  if (!used) return 0;
  const p = ps / used;
  const rr = rs / used;
  if (!p && !rr) return 0;
  const b2 = 4;
  return (100 * (1 + b2) * p * rr) / (b2 * p + rr);
}

const HAN = /[一-鿿]/;
const KANA = /[぀-ヿ]/;
const HANGUL = /[가-힣]/;

/** 그 언어 글자로 쓰였나. */
function rightScript(lang, out) {
  if (lang === 'ja') return KANA.test(out);
  if (lang === 'zh') return HAN.test(out) && !KANA.test(out);
  return /[A-Za-z]/.test(out) && !KANA.test(out) && !HAN.test(out);
}

/** 원문의 숫자 덩어리가 번역에 그대로 있나. '3,500' 은 '3500' 으로 쓴 것도 인정한다. */
function digitsKept(ko, out) {
  const chunks = ko.match(/\d[\d,.\-:]*\d|\d/g) ?? [];
  const flat = out.replace(/[,\s]/g, '');
  return chunks.every((c) => out.includes(c) || flat.includes(c.replace(/,/g, '')));
}

// --- 돌리기 ------------------------------------------------------------------------------------------------
console.log(`시험지 ${cases.length}문장 × ${LANGS.join('·')} = ${cases.length * LANGS.length}번 / 모델 하나, 지시문 ${PROMPTS.join(', ')}`);
console.log(`상한 ${MAX_WON}원 — 넘으면 그 자리에서 멈춘다`);
console.log('\n예상 값 (한 번에 입력 ~1,000토큰, 출력 ~60토큰으로 잡고):');
for (const m of models) {
  const price = PRICE[m.split('@')[0]];
  if (!price) { console.log(`   ${m.padEnd(26)} 값을 모르는 모델이다. 돌리기 전에 단가부터 확인해라.`); continue; }
  const won = ((1000 * price[0] + 60 * price[1]) / 1e6) * KRW * cases.length * LANGS.length * PROMPTS.length;
  console.log(`   ${m.padEnd(26)} 약 ${won.toFixed(0)}원`);
}
console.log();

const all = [];
const summary = [];
for (const spec of models) {
  for (const promptName of PROMPTS) {
    const started = Date.now();
    const jobs = cases.flatMap((c) => LANGS.map((lang) => ({ c, lang })));
    const answers = [];
    // 한 번에 몇 개씩만 보낸다. 몰아치면 끊긴다.
    for (let i = 0; i < jobs.length; i += 6) {
      answers.push(...await Promise.all(jobs.slice(i, i + 6).map(({ c, lang }) =>
        ask(spec, promptFor(promptName, lang), c.ko, c.ref[lang]))));
      if ((i + 6) % 120 < 6) process.stderr.write(`  ${Math.min(i + 6, jobs.length)}/${jobs.length}\n`);
    }

    const per = Object.fromEntries(LANGS.map((l) => [l, { n: 0, chrf: 0, script: 0, digits: 0, digitsN: 0, hangul: 0, err: 0, ms: 0 }]));
    const rows = [];
    jobs.forEach(({ c, lang }, i) => {
      const a = answers[i];
      const bucket = per[lang];
      if (a.error) { bucket.err++; rows.push({ id: c.id, lang, error: a.error }); return; }
      bucket.n++;
      const score = chrf(a.text, c.ref[lang]);
      bucket.chrf += score;
      bucket.ms += a.ms ?? 0;
      if (!rightScript(lang, a.text)) bucket.script++;
      if (HANGUL.test(a.text)) bucket.hangul++;
      if (/\d/.test(c.ko)) { bucket.digitsN++; if (!digitsKept(c.ko, a.text)) bucket.digits++; }
      rows.push({ id: c.id, cat: c.cat, lang, ko: c.ko, ref: c.ref[lang], hyp: a.text, chrf: score });
    });

    const spent = answers.reduce((acc, a) => ({ in: acc.in + (a.tokens?.in ?? 0), out: acc.out + (a.tokens?.out ?? 0) }), { in: 0, out: 0 });
    const price = PRICE[spec.split('@')[0]];
    const won = price ? ((spent.in * price[0] + spent.out * price[1]) / 1e6) * KRW : null;
    const secs = ((Date.now() - started) / 1000).toFixed(0);
    console.log(`== ${spec}  지시문 ${promptName}  (${secs}초, 토큰 입력 ${spent.in.toLocaleString()} 출력 ${spent.out.toLocaleString()}, ${won == null ? '값 모름' : won.toFixed(1) + '원'})`);
    for (const lang of LANGS) {
      const b = per[lang];
      const avg = b.n ? b.chrf / b.n : 0;
      console.log(
        `   ${lang}  chrF ${avg.toFixed(1)}  글자틀림 ${b.script}  숫자틀림 ${b.digits}/${b.digitsN}  한글남음 ${b.hangul}` +
          `  지연 ${b.n ? Math.round(b.ms / b.n) : 0}ms${b.err ? `  실패 ${b.err}건` : ''}`
      );
    }
    const mean = LANGS.reduce((s, l) => s + (per[l].n ? per[l].chrf / per[l].n : 0), 0) / LANGS.length;
    console.log(`   평균 chrF ${mean.toFixed(1)}   문장당 ${(won == null ? 0 : (won / Math.max(1, cases.length * LANGS.length))).toFixed(3)}원\n`);
    all.push({ model: spec, prompt: promptName, rows });
    summary.push(`${spec.padEnd(22)} ${promptName.padEnd(10)} ` + LANGS.map((l) => `${l} ${(per[l].n ? per[l].chrf / per[l].n : 0).toFixed(1)}${per[l].script ? `(글자틀림${per[l].script})` : ''}${per[l].hangul ? `(한글남음${per[l].hangul})` : ''}${per[l].err ? `(실패${per[l].err})` : ''}`).join('  ') + `   평균 ${mean.toFixed(1)}   ${(won == null ? 0 : won / Math.max(1, cases.length * LANGS.length)).toFixed(3)}원`);

    if (SHOW) {
      for (const r of rows) {
        if (r.error) { console.log(`ERR\t${spec}\t${promptName}\t${r.id}\t${r.lang}\t${r.error}`); continue; }
        console.log(`OUT\t${spec}\t${promptName}\t${r.id}\t${r.lang}\t${r.chrf.toFixed(0)}\t${r.ko}\t${r.ref}\t${r.hyp.replace(/\n/g, ' ⏎ ')}`);
      }
      console.log();
    }
  }
}
if (OUT) writeFileSync(OUT, JSON.stringify(all, null, 1));
console.log('\n=== 요약 (모델 / 지시문 / en·ja·zh chrF / 평균 / 문장당 원) ===');
for (const r of summary) console.log(r);
console.log(`쓴 돈 약 ${spentWon.toFixed(1)}원`);
