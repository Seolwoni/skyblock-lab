# Humanization

규칙은 `AGENTS.md`의 `## Humanization` 섹션을 따른다.

## 범위
포함: 감지, 정지, 응답, 재개 지연
제외: 패킷 지터, sub-tick

## 현황

| 항목 | 상태 | 위치 |
|---|---|---|
| 감지: 파티 초대, `From <name>:`, 스태프 태그 네임태그, warp 없는 30블록 텔레포트 | 구현 | `utils/WatchSigns.kt`, `AutoFishing` Failsafe 섹션 |
| 정지: 감지 시 즉시 정지 + 알림 + journal 기록 | 구현 | `AutoFishing.failStop` |
| 알림: 감지 사유·상대·위치·시각을 디스코드 웹훅으로 | 구현 | `AutoFishing.sendDiscord` |
| 이동 (Auto Routes): 웨이포인트 도착 오프셋, 출발 흔들림, 점프 지연, 재계산 주기 분산, 스프린트 지연, 정지 시 시선 랜덤워크, 걷기 피치 랜덤워크 | 구현 | `AutoRoutesMove.kt` (`arrivedAt`, `startWalking`, `jumpKey`, `sprintKey`, `idleLook`) |
| 던전 (Auto Dungeon / Auto Boss): `travelTick`을 거쳐 이동 항목 전부 적용, 도착 오프셋은 이동마다 새로 | 구현 | `AutoRoutes.travelTick` |
| 던전: 문 열기·키 줍기 클릭 주기 (`% 4`, `% 5` 고정) | 남음 | `AutoDungeon.kt:284`, `:313` |
| 퍼즐 (Auto Puzzles): 걷기 도착 오프셋(축당 최대 0.12, 같은 블록 유지), 스프린트 지연 | 구현 | `AutoPuzzles.walk` |
| 시선 직접 대입 (네 파일) | 없음 (전부 `Humanize` 경유) | - |

## 수치 (전부 설정값)

| 항목 | 기본값 | 설정 |
|---|---|---|
| 캐스트 실패 | 1.5% (2-4° 빗나감) | Auto Fishing › Mistakes › Cast Fail |
| 늦은 리일 | 2%, +7-14틱 | Late Reel / Late Reel By |
| 멍때림 | 캐스트당 3%, 3-8 s | Zone Out / Min / Max |
| 아이들 조준 | 랜덤워크, ±3° 경계 | Auto Fishing › Look › Movement Size / Drift Range |
| 클릭 면 내부 | ±0.12 | Human Behaviour › Aim Spread |
| 오버슈트 | 회전량(최대 10°)의 0.1-0.3, 20° 넘는 회전만 | Human Behaviour › Overshoot / Min / Max |
| 웨이포인트 도착 오프셋 | 축당 ±0.15, 반경은 그만큼 축소 (퍼즐은 축당 0.12) | Human Behaviour › Movement › Waypoint Offset |
| 출발 흔들림 | slipChance 확률로 ±1.5°, 6틱에 걸쳐 사라짐 | Start Wobble (확률은 `HumanProfile.slipChance`) |
| 점프 지연 | 로그정규 중앙값 1.8틱, σ 0.6, 0-5틱 | Jump Delay / Jump Spread / Jump Delay Max |
| 재계산 주기 | 10틱 ±30% (막힘 점프·Assist 전환 기준도 같은 분산) | Re-path Every / Re-path Spread |
| 스프린트 지연 | 1-3틱 | Sprint Delay Min / Max |
| 정지 시 시선 | 랜덤워크 ±2°, 걸음 0.3° (걷기 피치도 같음) | Idle Drift / Idle Step |

순수 수학: `utils/HumanMotion.kt`, `utils/WatchSigns.kt` ↔ `src/test/kotlin/human/HumanMotionTest.kt`, `WatchSignsTest.kt`.
