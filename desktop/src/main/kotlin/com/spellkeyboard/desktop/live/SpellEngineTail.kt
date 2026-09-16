package com.spellkeyboard.desktop.live

import com.spellkeyboard.desktop.SpellEngine

/**
 * [LiveCorrector] 를 앱의 [SpellEngine] 에 잇는다.
 *
 * ## EDT 를 막지 않는 것이 전부다
 *
 * `SpellEngine.correctAll` 은 곧바로 돌아오고 일꾼 스레드 하나에 줄을 세운다. 결과는
 * `toUi`(기본값 `invokeLater`)를 거쳐 EDT 로 돌아온다. 그래서 키를 누르는 순간 하는 일은
 * 문자열 몇십 글자를 잘라 큐에 넣는 것뿐이고, 사전이 아직 안 올라왔어도 줄만 설 뿐이다.
 *
 * ## 왜 `TypingSession` 을 쓰지 않는가 — 이 전략에서는 쓸 수 없다
 *
 * `TypingSession.pressSpace` 는 그 안에서 `engine.correctTail` 을 **동기로** 부른다.
 * EDT 에서 부르면 EDT 가 멈추고(문맥 교정기는 수십 ms 를 쓴다), 일꾼에서 부르면
 * 스레드 안전하지 않은 `TypingSession` 을 두 스레드가 만진다. 둘 다 규칙 위반이라
 * 이 전략은 엔진을 직접 부른다. 대신 `TypingSession` 이 주던 것 — 교정 창 자르기,
 * 되돌리기 장부 — 은 [planTail] 과 [LiveCorrector] 가 같은 규칙으로 다시 세웠다.
 *
 * ## [TailPlan.contextBefore] 를 일부러 버린다
 *
 * 넘길 자리가 있기는 하다 — `CorrectionEngine.correct(text, contextBefore)` 다. 그런데
 * 그 길로 가면 [NBestCorrector] 의 재분절과 붙여 쓴 글 전처리를 통째로 잃는다. 창 하나에
 * 대고 재 보면 그쪽이 훨씬 크다: `correctAll` 로 가면 `아버지가방에들어가신다` 가
 * `아버지가 방에 들어가신다` 로 풀리지만, `correct` 는 손도 못 댄다. 앞 어절 하나를 더
 * 보는 이득보다 재분절이 크므로 [전체교정] 과 **같은 파이프라인**을 쓴다.
 *
 * 남는 차이는 창의 크기뿐이다 — 실시간은 커서 앞 세 어절, [전체교정] 은 글 전체.
 * 그래서 [전체교정] 은 여전히 더 세고, 창에 남아 있어야 한다.
 */
fun spellEngineTail(engine: SpellEngine): TailCorrector =
    TailCorrector { window, _, onResult ->
        engine.correctAll(window) { corrected -> onResult(corrected.result) }
    }
