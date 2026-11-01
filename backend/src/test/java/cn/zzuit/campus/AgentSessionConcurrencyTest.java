package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.zzuit.campus.agent.AgentSessions;
import cn.zzuit.campus.agent.AgentSessionModels.Session;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AgentSessionConcurrencyTest {
    @Test void deletionReservesUserOperationUntilTransactionCompletes() throws Exception {
        var jdbc=mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var manager=mock(PlatformTransactionManager.class);
        var entered=new CountDownLatch(1); var resume=new CountDownLatch(1);
        when(manager.getTransaction(any())).thenAnswer(invocation->{
            entered.countDown();
            if(!resume.await(10,TimeUnit.SECONDS)) throw new AssertionError("transaction did not resume");
            return new SimpleTransactionStatus();
        });
        var sessions=spy(new AgentSessions(jdbc,null,null,null,null,null,null,null,null,null,null,manager,null));
        var user=new CampusPrincipal(7,"test","",true,List.of(),List.of("agent:query"),0);
        doReturn(new Session(1,"test",LocalDateTime.now(),LocalDateTime.now())).when(sessions).session(user,1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var deleting=executor.submit(()->sessions.delete(user,1));
            if(!entered.await(10,TimeUnit.SECONDS)) throw new AssertionError("deletion did not start");
            assertThatThrownBy(()->sessions.query(user,1,null)).isInstanceOf(ApiException.class);
            resume.countDown();
            deleting.get(10,TimeUnit.SECONDS);
        } finally { resume.countDown(); executor.shutdownNow(); }
    }
}
