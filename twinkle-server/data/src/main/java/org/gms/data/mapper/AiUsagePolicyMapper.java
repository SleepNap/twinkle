package org.gms.data.mapper;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.gms.data.entity.AiUsagePolicy;

/** AI 策略 MyBatis-Flex mapper。 */
public interface AiUsagePolicyMapper extends BaseMapper<AiUsagePolicy> {

    /**
     * 原子累加日用量计数器。
     *
     * <p>本项目其它 mapper 都只用 {@link BaseMapper} + QueryWrapper，这里是唯一的注解 SQL：
     * 日用量是并发热点，读-改-写（先 select 再 update 整行）在并发调用下会 last-write-wins
     * 丢计数，使预算被低估。{@code SET x = x + ?} 由数据库保证原子性，三方言语法一致。
     * 参数全部走 {@code #{}} 占位，不做字符串拼接。
     *
     * @return 受影响行数；0 表示该账号没有策略行（视为不限制，无需计数）
     */
    @Update("UPDATE ai_usage_policy SET daily_point_used = daily_point_used + #{points}, "
            + "daily_call_used = daily_call_used + #{calls}, "
            + "daily_token_used = daily_token_used + #{tokens}, "
            + "updated_at = #{updatedAt} WHERE account_id = #{accountId}")
    public int addDailyUsage(@Param("accountId") Long accountId,
                             @Param("points") long points,
                             @Param("calls") long calls,
                             @Param("tokens") long tokens,
                             @Param("updatedAt") String updatedAt);

    /**
     * 原子重置日窗口：仅当窗口起点仍是读到的旧值时才重置，避免并发下重复清零丢用量。
     *
     * @return 受影响行数；0 表示已被其它线程重置过
     */
    @Update("UPDATE ai_usage_policy SET daily_point_used = 0, daily_call_used = 0, "
            + "daily_token_used = 0, window_start = #{newWindowStart} "
            + "WHERE account_id = #{accountId} "
            + "AND (window_start = #{oldWindowStart} OR (window_start IS NULL AND #{oldWindowStart} IS NULL))")
    public int resetDailyWindow(@Param("accountId") Long accountId,
                                @Param("oldWindowStart") String oldWindowStart,
                                @Param("newWindowStart") String newWindowStart);
}
