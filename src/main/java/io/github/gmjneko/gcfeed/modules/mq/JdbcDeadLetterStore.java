package io.github.gmjneko.gcfeed.modules.mq;

import io.github.gmjneko.gcfeed.modules.mq.entity.GovernanceDeadLetter;
import io.github.gmjneko.gcfeed.modules.mq.mapper.GovernanceDeadLetterMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** 死信落库实现：整条原始消息体原样保存，供人工排查或后续重放。 */
@Repository
public class JdbcDeadLetterStore implements DeadLetterStore {

    private final GovernanceDeadLetterMapper mapper;

    public JdbcDeadLetterStore(GovernanceDeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(String taskType, String payload, int retryCount, String lastError) {
        GovernanceDeadLetter row = new GovernanceDeadLetter();
        row.setTaskType(taskType);
        // 消息体原样入库：死信表是重放时唯一的事实来源，不需要从别处重新拼装。
        row.setPayload(payload == null ? "{}" : payload);
        row.setRetryCount(retryCount);
        row.setStatus(GovernanceDeadLetter.ABANDONED);
        // last_error 列限 512 字符，截断而不是失败：记死信这个动作本身不允许再出错。
        String error = lastError == null ? "" : lastError;
        row.setLastError(error.length() > 512 ? error.substring(0, 512) : error);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
    }
}
