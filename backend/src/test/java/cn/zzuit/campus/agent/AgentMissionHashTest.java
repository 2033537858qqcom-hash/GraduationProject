package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentMissionHashTest {
 final AgentMissionTools tools=new AgentMissionTools(null,null,null,null,null,null,null,null);
 @Test void persistedConfirmationDigestIsIndependentOfMapIterationOrder(){
  var first=new LinkedHashMap<String,Object>();first.put("kind","CERTIFICATE");first.put("identity",new LinkedHashMap<>(Map.of("department",1,"class",2)));first.put("user",7);
  var nested=new LinkedHashMap<String,Object>();nested.put("class",2);nested.put("department",1);var second=new LinkedHashMap<String,Object>();second.put("user",7);second.put("identity",nested);second.put("kind","CERTIFICATE");
  assertThat(tools.hash(first)).isEqualTo(tools.hash(second));second.put("user",8);assertThat(tools.hash(first)).isNotEqualTo(tools.hash(second));
 }
}
