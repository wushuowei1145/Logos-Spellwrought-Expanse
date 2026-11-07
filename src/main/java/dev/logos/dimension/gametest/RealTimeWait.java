package dev.logos.dimension.gametest;

import net.minecraft.gametest.framework.GameTestAssertException;

/**
 * 等要花真实时间的事（光照传播、区块生成和升级）时用，包住 {@code succeedWhen}、{@code thenWaitUntil} 的断言。
 *
 * <p>测试的时限按刻算，而 {@code GameTestServer} 每刻之间只把积压的任务跑完、不等下一刻，空闲时每秒能跑几千刻；
 * 光照和区块生成却在后台线程池里排队，和别的测试生成区块的任务抢线程。CI 机器忙时 6000 刻约一秒多就跑完了，后台活还没干完就超时。
 * 这里让每个没等到结果的刻多睡 10 毫秒，时限因此有了真实时间的下限（1600 刻至少 16 秒，6000 刻至少 1 分钟），
 * 不再取决于刻跑得多快。断言本身不变，没通过时照常抛出，下一刻重试；等到之后不再睡，后面的步骤仍按刻走。
 */
final class RealTimeWait {
    private static final long MILLIS_PER_WAITING_TICK = 10;

    static Runnable paced(Runnable assertions) {
        return () -> {
            try {
                assertions.run();
            } catch (GameTestAssertException notYet) {
                try {
                    Thread.sleep(MILLIS_PER_WAITING_TICK);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw notYet;
            }
        };
    }

    private RealTimeWait() {}
}
