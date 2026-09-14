/**
 * 깨끗한 문장에 **실제로 폰에서 나는 오타**를 하나씩 심는다.
 *
 * 온디바이스 엔진을 잴 때 쓴 것과 같은 네 갈래다. 같은 자로 재야 "AI 가 온디바이스보다
 * 나은가" 를 말할 수 있다.
 */

const BASE = 0xac00;
const LAST = 0xd7a3;

const hangul = (ch) => ch >= BASE && ch <= LAST;
const split = (code) => {
  const i = code - BASE;
  return [Math.floor(i / 588), Math.floor((i % 588) / 28), i % 28];
};
const join = (a, b, c) => String.fromCharCode(BASE + a * 588 + b * 28 + c);

/** 흔들 자리를 고른다. 없으면 null. */
function pick(text, ok, rnd) {
  const spots = [];
  for (let i = 0; i < text.length; i++) if (ok(text.charCodeAt(i), i)) spots.push(i);
  return spots.length ? spots[Math.floor(rnd() * spots.length)] : null;
}

/** ㅐ ↔ ㅔ. 소리가 거의 같아서 한국어에서 제일 흔한 표기 혼동이다. */
export function flipAe(text, rnd) {
  const AE = 1, E = 5; // ㅐ, ㅔ
  const at = pick(text, (code) => {
    if (!hangul(code)) return false;
    const [, v] = split(code);
    return v === AE || v === E;
  }, rnd);
  if (at === null) return null;
  const [a, v, c] = split(text.charCodeAt(at));
  return text.slice(0, at) + join(a, v === AE ? E : AE, c) + text.slice(at + 1);
}

/** 받침을 빠뜨린다. 두벌식에서 받침은 키를 한 번 더 눌러야 해서 흔히 샌다. */
export function dropCoda(text, rnd) {
  const at = pick(text, (code) => hangul(code) && split(code)[2] !== 0, rnd);
  if (at === null) return null;
  const [a, v] = split(text.charCodeAt(at));
  return text.slice(0, at) + join(a, v, 0) + text.slice(at + 1);
}

/** 두벌식에서 맞닿은 키를 누른다. 손가락이 빗나가서 나는, 폰에서 제일 흔한 오타다. */
const NEAR_CONSONANT = {
  ㅂ: 'ㅈㅁ', ㅈ: 'ㅂㄷㄴ', ㄷ: 'ㅈㄱㅇ', ㄱ: 'ㄷㅅㄹ', ㅅ: 'ㄱㅛㅎ',
  ㅁ: 'ㅂㄴ', ㄴ: 'ㅁㅇㅈ', ㅇ: 'ㄴㄹㄷ', ㄹ: 'ㅇㅎㄱ', ㅎ: 'ㄹㅗㅅ',
  ㅋ: 'ㅌ', ㅌ: 'ㅋㅊ', ㅊ: 'ㅌㅍ', ㅍ: 'ㅊ',
};
const NEAR_VOWEL = {
  ㅛ: 'ㅅㅕㅎ', ㅕ: 'ㅛㅑㅓ', ㅑ: 'ㅕㅐㅏ', ㅐ: 'ㅑㅔㅣ', ㅔ: 'ㅐㅣ',
  ㅗ: 'ㅎㅓㅍ', ㅓ: 'ㅗㅏㅕ', ㅏ: 'ㅓㅣㅑ', ㅣ: 'ㅏㅐ',
  ㅜ: 'ㅠㅡ', ㅠ: 'ㅜ', ㅡ: 'ㅜ',
};
const CHO = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';
const JUNG = 'ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ';

export function nearKey(text, rnd) {
  const spots = [];
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (!hangul(code)) continue;
    const [a, v] = split(code);
    if (NEAR_CONSONANT[CHO[a]]) spots.push([i, 'cho']);
    if (NEAR_VOWEL[JUNG[v]]) spots.push([i, 'jung']);
  }
  if (!spots.length) return null;
  const [at, part] = spots[Math.floor(rnd() * spots.length)];
  const [a, v, c] = split(text.charCodeAt(at));
  if (part === 'cho') {
    const near = NEAR_CONSONANT[CHO[a]];
    const to = CHO.indexOf(near[Math.floor(rnd() * near.length)]);
    if (to < 0) return null;
    return text.slice(0, at) + join(to, v, c) + text.slice(at + 1);
  }
  const near = NEAR_VOWEL[JUNG[v]];
  const to = JUNG.indexOf(near[Math.floor(rnd() * near.length)]);
  if (to < 0) return null;
  return text.slice(0, at) + join(a, to, c) + text.slice(at + 1);
}

/** 띄어쓰기 하나를 지운다. */
export function dropSpace(text, rnd) {
  const spots = [];
  for (let i = 0; i < text.length; i++) if (text[i] === ' ') spots.push(i);
  if (!spots.length) return null;
  const at = spots[Math.floor(rnd() * spots.length)];
  return text.slice(0, at) + text.slice(at + 1);
}

/** 띄어쓰기를 전부 지운다. 카톡에서 급하게 칠 때 실제로 이렇게 온다. */
export function dropAllSpaces(text) {
  return text.replace(/ /g, '');
}

export const KINDS = {
  'ㅐ/ㅔ 뒤집기': flipAe,
  '받침 떨구기': dropCoda,
  '옆 키': nearKey,
  '띄어쓰기 하나': dropSpace,
};

/** 씨를 고정한 난수. 누가 언제 돌려도 같은 시험지가 나와야 한다. */
export function seeded(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}
