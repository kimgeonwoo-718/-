package com.spellkeyboard.desktop.floating

/**
 * 창이 어디에 뜰지 고르는 방식.
 *
 * ## 왜 [FOLLOW_POINTER] 가 기본인가 — 세 가지를 재 보고 고른 것이다
 *
 * 이 기계의 모니터는 셋이고, 나란히 붙어 있지도 않다:
 *
 *     DISPLAY3  x=-1920  1920x1080        DISPLAY2(주)  x=0  1920x1080
 *     DISPLAY1  x=+1920  1536x864  ← 배율이 달라 논리 크기가 작다
 *
 * 떠 있는 창은 **다른 프로그램에서 일하다가 단축키로 부르는 창**이다. 부르는 순간
 * 사용자의 눈은 자기가 치던 곳에 있다.
 *
 * - `CENTER_PRIMARY` 는 주 모니터에 뜬다. 왼쪽 끝 모니터에서 글을 쓰다 부르면 창이
 *   1920px 떨어진 곳에 뜬다. 사용자는 창이 안 떴다고 생각한다.
 * - `REMEMBER` 는 마지막에 있던 자리에 뜬다. 같은 문제에 더해, 그 모니터를 빼 버리면
 *   창이 아무 데도 없는 좌표로 간다(그래서 [placeFloating] 이 다시 집을 찾아 준다).
 * - [FOLLOW_POINTER] 는 **마우스가 있는 모니터**에 뜬다. 키보드 초점이 아니라 마우스를
 *   보는 까닭은 `GetForegroundWindow` 가 가리키는 창이 여러 모니터에 걸쳐 있을 수도,
 *   최소화돼 있을 수도 있는 반면 포인터는 언제나 정확히 한 모니터 위에 있기 때문이다.
 *   실제로도 방금까지 손이 있던 곳이 마우스가 있는 곳이다.
 *
 * 셋 다 만들어 둔 것은 이 취향이 갈리기 때문이다. 초점 잃으면 숨기기와 마찬가지로
 * 설정에서 바꾼다. 다만 **기본은 [FOLLOW_POINTER]** 다.
 */
enum class PlacementPolicy {
    /** 마우스가 있는 모니터. 사용자가 옮겨 둔 자리는 그 모니터 안의 **비율**로 따라간다. */
    FOLLOW_POINTER,

    /** 마지막으로 있던 자리 그대로. 모니터가 사라졌으면 마우스 쪽으로 데려온다. */
    REMEMBER,

    /** 언제나 주 모니터 한가운데. */
    CENTER_PRIMARY,
    ;

    companion object {
        /** 설정값이 상했거나 예전 이름이면 기본으로 돌린다. 던지지 않는다. */
        fun decode(name: String?): PlacementPolicy =
            entries.firstOrNull { it.name == name } ?: FOLLOW_POINTER
    }
}

/**
 * 창을 놓을 자리를 셈한다. **순수 함수다** — 화면도, EDT 도, 시계도 필요 없다.
 *
 * @param size 창 크기. 336x324.
 * @param screens 붙어 있는 모니터 전부. 비어 있으면(원격 접속이 끊긴 순간 등)
 *   있던 자리나 0,0 을 돌려준다 — 여기서 터지면 단축키가 죽는다.
 * @param pointer 마우스 위치. `MouseInfo` 는 **null 을 줄 수 있다**(화면 잠김, 마우스 없음).
 * @param remembered 사용자가 마지막으로 창을 둔 자리. 없으면 첫 소환이다.
 */
fun placeFloating(
    policy: PlacementPolicy,
    size: Rect,
    screens: List<ScreenBox>,
    pointer: Pt?,
    remembered: Rect?,
): Rect {
    if (screens.isEmpty()) return remembered?.at(remembered.x, remembered.y) ?: Rect(0, 0, size.width, size.height)
    val box = Rect(0, 0, size.width, size.height)

    return when (policy) {
        PlacementPolicy.CENTER_PRIMARY -> {
            val work = (screens.firstOrNull { it.primary } ?: screens.first()).workArea
            box.at(work.x + (work.width - box.width) / 2, work.y + (work.height - box.height) / 2)
                .clampInto(work)
        }

        PlacementPolicy.REMEMBER -> {
            // 적어 둔 자리가 지금도 어느 모니터엔가 걸쳐 있는지 본다. 노트북에서
            // 외부 모니터를 빼면 걸쳐 있지 않게 되고, 그대로 두면 창이 화면 밖에 뜬다.
            val home = remembered?.let { r -> screens.maxByOrNull { it.bounds.overlap(r) } }
                ?.takeIf { it.bounds.overlap(remembered) > 0 }
            if (remembered != null && home != null) {
                box.at(remembered.x, remembered.y).clampInto(home.workArea)
            } else {
                // 집이 없어졌다. 마우스 쪽으로 데려온다 — 규칙을 어기는 것이 아니라
                // 안 보이는 창보다 낫다.
                defaultSpot(box, screenFor(screens, pointer).workArea)
            }
        }

        PlacementPolicy.FOLLOW_POINTER -> {
            val work = screenFor(screens, pointer).workArea
            val offset = remembered?.let { relativeOffset(it, screens) }
            if (offset == null) defaultSpot(box, work) else applyOffset(box, work, offset)
        }
    }
}

/**
 * 포인터가 올라앉은 모니터. 모니터 사이에 틈이 있는 배치에서는 어느 것에도 안 들어갈
 * 수 있으므로(그리고 pointer 가 null 일 수 있으므로) 늘 하나는 돌려준다.
 */
private fun screenFor(screens: List<ScreenBox>, pointer: Pt?): ScreenBox {
    if (pointer == null) return screens.firstOrNull { it.primary } ?: screens.first()
    return screens.firstOrNull { it.bounds.contains(pointer.x, pointer.y) }
        ?: screens.minByOrNull { it.bounds.distanceSquared(pointer.x, pointer.y) }
        ?: screens.first()
}

/**
 * 첫 소환 자리: 가로 한가운데, 세로는 **남는 자리의 1/3 지점.**
 *
 * 정확히 한가운데가 아닌 까닭은, 고친 글을 받아 갈 원래 창이 보통 화면 아래쪽에 있기
 * 때문이다. 살짝 위에 띄우면 뒤가 덜 가린다. Spotlight 나 Alfred 도 같은 자리에 뜬다.
 */
private fun defaultSpot(box: Rect, work: Rect): Rect =
    box.at(
        work.x + (work.width - box.width) / 2,
        work.y + (work.height - box.height) / 3,
    ).clampInto(work)

/**
 * 사용자가 옮겨 둔 자리를 **그 모니터 안에서의 비율**로 바꾼다.
 *
 * 절대 좌표를 다른 모니터에 그대로 쓰면 안 된다. 이 기계의 DISPLAY1 은 논리 크기가
 * 1536x864 라 주 모니터의 오른쪽 아래 자리가 거기서는 화면 밖이다. 남는 자리에 대한
 * 비율로 옮기면 "오른쪽 아래에 두었다" 는 뜻이 모니터가 달라도 그대로 산다.
 */
private fun relativeOffset(remembered: Rect, screens: List<ScreenBox>): Pair<Double, Double>? {
    val home = screens.maxByOrNull { it.bounds.overlap(remembered) } ?: return null
    if (home.bounds.overlap(remembered) <= 0) return null
    val work = home.workArea
    val fx = fraction(remembered.x - work.x, work.width - remembered.width)
    val fy = fraction(remembered.y - work.y, work.height - remembered.height)
    return fx to fy
}

private fun applyOffset(box: Rect, work: Rect, offset: Pair<Double, Double>): Rect =
    box.at(
        work.x + Math.round(offset.first * (work.width - box.width)).toInt(),
        work.y + Math.round(offset.second * (work.height - box.height)).toInt(),
    ).clampInto(work)

/** 남는 자리가 0 이하면(창이 모니터보다 큼) 비율을 따질 것이 없다. */
private fun fraction(offset: Int, free: Int): Double =
    if (free <= 0) 0.0 else (offset.toDouble() / free).coerceIn(0.0, 1.0)
