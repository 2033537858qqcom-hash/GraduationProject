package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.housing.HousingMaterialContent;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-housing-material;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"})
class HousingMaterialIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/housing/transfers";

    @Test void draftUploadUsesActualFormatAndPrivateDownloadHeaders() throws Exception {
        var f=fixture();byte[] bytes=png();
        var upload=ok(upload(f.student(),f.id(),0,key(),"证明.exe",bytes));
        assertThat(upload.path("version").asInt()).isEqualTo(1);assertThat(upload.path("canEdit").asBoolean()).isTrue();
        var file=upload.path("current").get(0);assertThat(file.path("name").asText()).isEqualTo("证明.png");
        assertThat(file.path("type").asText()).isEqualTo("image/png");
        var download=binary(f.student(),f.id(),file.path("id").asLong(),"mine",null);
        assertThat(download.statusCode()).isEqualTo(200);assertThat(download.body()).isEqualTo(bytes);
        assertThat(download.headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(download.headers().firstValue("content-disposition").orElse("")).startsWith("attachment;");
        assertThat(download.headers().firstValue("x-content-type-options").orElse("")).isEqualTo("nosniff");
        assertThat(download.headers().firstValue("content-security-policy").orElse("")).isEqualTo("sandbox");
        assertThat(upload.toString()).doesNotContain("fileBytes","file_sha256","identityNumber");
        assertThat(HousingMaterialContent.name("../恶意\r\n名.html","application/pdf")).doesNotContain("/","\r","\n").endsWith(".pdf");
    }
    @Test void resubmissionFreezesNewFilesAndPreservesTheRemovedOriginal() throws Exception {
        var f=fixture();var student=f.student();var reviewer=login("affairs1001");
        long old=ok(upload(student,f.id(),0,key(),"第一版.pdf",pdf(1,false))).path("current").get(0).path("id").asLong();
        var submit=action(version(f.id()),"提交");ok(student.send("POST",ROOT+"/mine/"+f.id()+"/submit",submit));
        ok(student.send("POST",ROOT+"/mine/"+f.id()+"/submit",submit));
        var first=materials(reviewer,f.id(),"review");assertThat(first.path("total").asInt()).isEqualTo(1);assertThat(first.path("current").isEmpty()).isTrue();
        long firstRevision=first.path("submissions").get(0).path("id").asLong();
        assertThat(upload(student,f.id(),version(f.id()),key(),"不能添加.png",png()).statusCode()).isEqualTo(409);
        assertThat(student.send("POST",ROOT+"/mine/"+f.id()+"/materials/"+old+"/remove",action(version(f.id()),"移除")).statusCode()).isEqualTo(409);
        ok(reviewer.send("POST",ROOT+"/review/"+f.id()+"/return",action(version(f.id()),"请补充材料")));
        ok(student.send("POST",ROOT+"/mine/"+f.id()+"/materials/"+old+"/remove",action(version(f.id()),"替换材料")));
        long replacement=ok(upload(student,f.id(),version(f.id()),key(),"第二版.png",png())).path("current").get(0).path("id").asLong();
        assertThat(materials(reviewer,f.id(),"review").toString()).doesNotContain("第二版.png");
        assertThat(binary(reviewer,f.id(),replacement,"review",null).statusCode()).isEqualTo(404);
        assertThat(binary(reviewer,f.id(),old,"review",firstRevision).statusCode()).isEqualTo(200);
        var supplement=draft();supplement.put("version",version(f.id()));supplement.put("reason","补充后的新原因");supplement.put("materialNote","新版附件已核对");
        ok(student.send("POST",ROOT+"/mine/"+f.id()+"/supplement",supplement));ok(student.send("POST",ROOT+"/mine/"+f.id()+"/supplement",supplement));
        var second=materials(reviewer,f.id(),"review");assertThat(second.path("total").asInt()).isEqualTo(2);
        assertThat(second.path("submissions").get(0).path("files").get(0).path("id").asLong()).isEqualTo(replacement);
        assertThat(second.path("submissions").get(1).path("files").get(0).path("id").asLong()).isEqualTo(old);
        assertThat(second.path("submissions").get(0).path("reason").asText()).isEqualTo("补充后的新原因");
        assertThat(second.path("submissions").get(1).path("reason").asText()).contains("quieter");
        assertThat(binary(reviewer,f.id(),old,"review",null).statusCode()).isEqualTo(404);
        assertThat(binary(reviewer,f.id(),old,"review",firstRevision).statusCode()).isEqualTo(200);
        assertThat(binary(student,f.id(),old,"mine",firstRevision).statusCode()).isEqualTo(200);
        var page=ok(reviewer.get(ROOT+"/review/"+f.id()+"/materials?pageSize=1&page=2"));assertThat(page.path("submissions").size()).isEqualTo(1);assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(reviewer.get(ROOT+"/review/"+f.id()+"/materials?pageSize=21").statusCode()).isEqualTo(400);
    }
    @Test void draftsAndMaterialsStayPrivateAcrossRolesAndCollegeChanges() throws Exception {
        var f=fixture();long file=ok(upload(f.student(),f.id(),0,key(),"私有材料.png",png())).path("current").get(0).path("id").asLong();
        assertThat(login("affairs1001").get(ROOT+"/review/"+f.id()+"/materials").statusCode()).isEqualTo(404);
        var other=login(student());assertThat(other.get(ROOT+"/mine/"+f.id()+"/materials").statusCode()).isEqualTo(404);
        assertThat(binary(other,f.id(),file,"mine",null).statusCode()).isEqualTo(404);
        for(String name:List.of("admin","dorm1001","teacher1001")) assertThat(binary(login(name),f.id(),file,"mine",null).statusCode()).isEqualTo(403);
        String reviewerName=account("hfr","材料审核员","affairs1001","STUDENT_AFFAIRS");var reviewer=login(reviewerName);
        ok(f.student().send("POST",ROOT+"/mine/"+f.id()+"/submit",action(version(f.id()),"提交")));
        assertThat(binary(reviewer,f.id(),file,"review",null).statusCode()).isEqualTo(200);
        assertThat(binary(login("dorm1001"),f.id(),file,"review",null).statusCode()).isEqualTo(403);
        jdbc.sql("UPDATE app_user SET department_id=2 WHERE username=:name").param("name",reviewerName).update();
        assertThat(binary(reviewer,f.id(),file,"review",null).statusCode()).isEqualTo(404);
        jdbc.sql("DELETE FROM user_role WHERE user_id=:user").param("user",user(f.name())).update();
        assertThat(binary(login(f.name()),f.id(),file,"mine",null).statusCode()).isEqualTo(403);
    }
    @Test void fileAndSubmissionIdsCannotCrossRequests() throws Exception {
        var left=fixture();var right=fixture();
        long file=ok(upload(left.student(),left.id(),0,key(),"左侧.png",png())).path("current").get(0).path("id").asLong();
        ok(left.student().send("POST",ROOT+"/mine/"+left.id()+"/submit",action(version(left.id()),"提交")));
        ok(right.student().send("POST",ROOT+"/mine/"+right.id()+"/submit",action(version(right.id()),"提交")));
        long revision=materials(right.student(),right.id(),"mine").path("submissions").get(0).path("id").asLong();
        assertThat(binary(left.student(),left.id(),file,"mine",revision).statusCode()).isEqualTo(404);
        assertThat(binary(right.student(),right.id(),file,"mine",null).statusCode()).isEqualTo(404);
        assertThat(right.student().send("POST",ROOT+"/mine/"+right.id()+"/materials/"+file+"/remove",action(version(right.id()),"移除")).statusCode()).isEqualTo(409);
        assertThat(binary(login("affairs1001"),right.id(),file,"review",revision).statusCode()).isEqualTo(404);
    }
    @Test void uploadLimitsAndRetriesDoNotCreateExtraFiles() throws Exception {
        var f=fixture();String requestKey=key();byte[] bytes=png();
        ok(upload(f.student(),f.id(),0,requestKey,"相同.png",bytes));ok(upload(f.student(),f.id(),0,requestKey,"相同.png",bytes));
        assertThat(fileCount(f.id())).isEqualTo(1);assertThat(version(f.id())).isEqualTo(1);
        String fingerprint=jdbc.sql("SELECT input_digest FROM housing_transfer_action WHERE actor_id=:user AND request_key=:key").param("user",user(f.name())).param("key",requestKey).query(String.class).single();
        String[] parts=fingerprint.split("\\|",3);var crossAction=draft();crossAction.put("requestKey",requestKey);crossAction.put("reason",parts[0]);crossAction.put("preferredArea",parts[1]);crossAction.put("materialNote",parts[2]);
        assertThat(f.student().send("POST",ROOT+"/mine",crossAction).statusCode()).isEqualTo(409);
        assertThat(upload(f.student(),f.id(),0,requestKey,"不同.png",bytes).statusCode()).isEqualTo(409);
        assertThat(upload(f.student(),f.id(),0,key(),"过期.png",bytes).statusCode()).isEqualTo(409);
        ok(upload(f.student(),f.id(),version(f.id()),key(),"二.png",bytes));ok(upload(f.student(),f.id(),version(f.id()),key(),"三.png",bytes));
        assertThat(upload(f.student(),f.id(),version(f.id()),key(),"四.png",bytes).statusCode()).isEqualTo(409);
        assertThat(fileCount(f.id())).isEqualTo(3);
        long first=materials(f.student(),f.id(),"mine").path("current").get(0).path("id").asLong();
        var remove=action(version(f.id()),"移除");ok(f.student().send("POST",ROOT+"/mine/"+f.id()+"/materials/"+first+"/remove",remove));ok(f.student().send("POST",ROOT+"/mine/"+f.id()+"/materials/"+first+"/remove",remove));
        assertThat(binary(f.student(),f.id(),first,"mine",null).statusCode()).isEqualTo(404);
        ok(upload(f.student(),f.id(),version(f.id()),key(),"可替换.png",bytes));
        assertThat(materials(f.student(),f.id(),"mine").path("current").size()).isEqualTo(3);
    }
    @Test void invalidFormatsAndOversizedOrEncryptedDocumentsAreRejected() throws Exception {
        var f=fixture();
        for(byte[] bytes:List.of(new byte[0],"<svg/>".getBytes(StandardCharsets.UTF_8),"%PDF-not-a-document".getBytes(StandardCharsets.UTF_8),new byte[]{(byte)137,80,78,71},pdf(21,false),pdf(1,true))) {
            assertThat(upload(f.student(),f.id(),0,key(),"伪装.pdf",bytes).statusCode()).isEqualTo(400);
        }
        assertThat(upload(f.student(),f.id(),0,key(),"过大.png",new byte[2*1024*1024+1]).statusCode()).isEqualTo(413);
        assertThat(fileCount(f.id())).isZero();assertThat(version(f.id())).isZero();
        assertThat(ok(upload(f.student(),f.id(),0,key(),"合法.pdf",pdf(1,false))).path("current").get(0).path("type").asText()).isEqualTo("application/pdf");
    }
    @Test void simultaneousUploadsAndSubmissionKeepOneConsistentVersion() throws Exception {
        var f=fixture();byte[] bytes=png();
        var one=CompletableFuture.supplyAsync(()->uploadUnchecked(f.student(),f.id(),0,key(),bytes));
        var two=CompletableFuture.supplyAsync(()->uploadUnchecked(f.student(),f.id(),0,key(),bytes));
        assertThat(List.of(one.get().statusCode(),two.get().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(fileCount(f.id())).isEqualTo(1);int expected=version(f.id());
        var upload=CompletableFuture.supplyAsync(()->uploadUnchecked(f.student(),f.id(),expected,key(),bytes));
        var submit=CompletableFuture.supplyAsync(()->sendUnchecked(f.student(),"POST",ROOT+"/mine/"+f.id()+"/submit",action(expected,"提交")));
        assertThat(List.of(upload.get().statusCode(),submit.get().statusCode())).containsExactlyInAnyOrder(200,409);
        if(submit.get().statusCode()==200) assertThat(materials(f.student(),f.id(),"mine").path("submissions").get(0).path("files").size()).isEqualTo(1);
        else assertThat(materials(f.student(),f.id(),"mine").path("total").asInt()).isZero();
    }
    @Test void deletingADraftRemovesItsPrivateFilesWithoutBreakingRepeatProtection() throws Exception {
        var f=fixture();long file=ok(upload(f.student(),f.id(),0,key(),"草稿.png",png())).path("current").get(0).path("id").asLong();
        var remove=action(version(f.id()),"删除草稿");ok(f.student().send("POST",ROOT+"/mine/"+f.id()+"/delete",remove));ok(f.student().send("POST",ROOT+"/mine/"+f.id()+"/delete",remove));
        assertThat(fileCount(f.id())).isZero();assertThat(binary(f.student(),f.id(),file,"mine",null).statusCode()).isEqualTo(404);
    }
    @Test void fileAndSnapshotWritesRollbackWithTheirBusinessTransaction() throws Exception {
        var f=fixture();
        jdbc.sql("ALTER TABLE housing_transfer_action ADD CONSTRAINT material_action_failure CHECK(transfer_id <> "+f.id()+" OR action <> 'MATERIAL_UPLOAD')").update();
        try {
            assertThat(upload(f.student(),f.id(),0,key(),"回滚.png",png()).statusCode()).isEqualTo(409);
            assertThat(fileCount(f.id())).isZero();assertThat(version(f.id())).isZero();
        } finally {jdbc.sql("ALTER TABLE housing_transfer_action DROP CONSTRAINT material_action_failure").update();}
        ok(upload(f.student(),f.id(),0,key(),"保留.png",png()));int expected=version(f.id());
        jdbc.sql("ALTER TABLE user_notice ADD CONSTRAINT material_notice_failure CHECK(source_type <> 'HOUSING' OR source_id <> "+f.id()+" OR source_view <> 'REVIEW')").update();
        try {
            assertThat(f.student().send("POST",ROOT+"/mine/"+f.id()+"/submit",action(expected,"提交")).statusCode()).isEqualTo(409);
            assertThat(version(f.id())).isEqualTo(expected);assertThat(materials(f.student(),f.id(),"mine").path("total").asInt()).isZero();assertThat(fileCount(f.id())).isEqualTo(1);
        } finally {jdbc.sql("ALTER TABLE user_notice DROP CONSTRAINT material_notice_failure").update();}
        ok(f.student().send("POST",ROOT+"/mine/"+f.id()+"/submit",action(expected,"再次提交")));
        assertThat(materials(f.student(),f.id(),"mine").path("total").asInt()).isEqualTo(1);
    }
    @Test void olderRequestsDoNotAcquireInventedSnapshotsAndTheGuideReflectsUploads() throws Exception {
        var f=fixture();jdbc.sql("UPDATE housing_transfer SET status='SUBMITTED',version=1 WHERE id=:id").param("id",f.id()).update();
        assertThat(materials(login("affairs1001"),f.id(),"review").path("total").asInt()).isZero();
        var guide=ok(f.student().get("/api/assistant/guides/housing"));assertThat(guide.path("body").asText()).contains("JPEG","提交和补交冻结").doesNotContain("本批不上传附件");
    }
    private record Fixture(String name,Browser student,long id) {}
    private Fixture fixture()throws Exception {String name=housedStudent(login("admin"),"dorm1001");var student=login(name);long id=ok(student.send("POST",ROOT+"/mine",draft())).path("id").asLong();return new Fixture(name,student,id);}
    private long fileCount(long id) {return jdbc.sql("SELECT COUNT(*) FROM housing_material WHERE transfer_id=:id").param("id",id).query(Long.class).single();}
    private JsonNode materials(Browser actor,long id,String view)throws Exception {return ok(actor.get(ROOT+"/"+view+"/"+id+"/materials"));}
    private HttpResponse<byte[]> binary(Browser actor,long id,long file,String view,Long submission)throws Exception {return actor.http.send(actor.builder(ROOT+"/"+view+"/"+id+"/materials/"+file+(submission==null?"":"?submission="+submission)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    private byte[] png()throws IOException {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    private byte[] pdf(int pages,boolean encrypted)throws IOException {try(var document=new PDDocument();var out=new ByteArrayOutputStream()){for(int i=0;i<pages;i++)document.addPage(new PDPage());if(encrypted){var policy=new StandardProtectionPolicy("owner","reader",new AccessPermission());policy.setEncryptionKeyLength(128);document.protect(policy);}document.save(out);return out.toByteArray();}}
    private HttpResponse<String> upload(Browser actor,long id,int version,String key,String name,byte[] bytes)throws Exception {
        String boundary="Housing"+UUID.randomUUID().toString().replace("-","");var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"+version+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"requestKey\"\r\n\r\n"+key+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return actor.http.send(actor.builder(ROOT+"/mine/"+id+"/materials").header(actor.header,actor.token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> uploadUnchecked(Browser actor,long id,int version,String key,byte[] bytes){try{return upload(actor,id,version,key,"并行.png",bytes);}catch(Exception ex){throw new RuntimeException(ex);}}
    private long reserve(Browser student,Browser affairs,Browser manager,long bed)throws Exception{long id=submitApproved(student,affairs);return ok(manager.send("POST",ROOT+"/assign/"+id+"/reserve",reserve(version(id),bed))).path("id").asLong();}
    private long submitApproved(Browser student,Browser affairs)throws Exception{var created=ok(student.send("POST",ROOT+"/mine",draft()));var submitted=ok(student.send("POST",ROOT+"/mine/"+created.path("id").asLong()+"/submit",action(created.path("version").asInt(),"提交")));return ok(affairs.send("POST",ROOT+"/review/"+created.path("id").asLong()+"/approve",action(submitted.path("version").asInt(),"同意"))).path("id").asLong();}
    private int version(long id){return jdbc.sql("SELECT version FROM housing_transfer WHERE id=:id").param("id",id).query(Integer.class).single();}
    private String housedStudent(Browser admin,String manager)throws Exception{String name=student();long building=building(admin,1);long room=room(admin,building);long bed=bed(admin,room,"A");ok(admin.send("POST","/api/admin/housing/scopes",Map.of("userId",user(manager),"buildingId",building,"version",0,"reason","演示范围原因")));ok(login(manager).send("POST","/api/housing/assignments",Map.of("requestKey",key(),"studentId",user(name),"bedId",bed,"reason","初始入住")));return name;}
    private long extraBed(Browser admin,long building)throws Exception{return bed(admin,room(admin,building),"B");}
    private long buildingOf(long bedId){return jdbc.sql("SELECT r.building_id FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id WHERE d.id=:id").param("id",bedId).query(Long.class).single();}
    private long currentBed(String name){return jdbc.sql("SELECT bed_id FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single();}
    private long currentAssignment(String name){return jdbc.sql("SELECT id FROM housing_assignment WHERE current_student_id=:id").param("id",user(name)).query(Long.class).single();}
    private long building(Browser admin,long campus)throws Exception{return ok(admin.send("POST","/api/admin/housing/buildings",Map.of("campusId",campus,"code","DORM-"+UUID.randomUUID().toString().substring(0,8),"name","调宿测试楼","provenance","DEMO","version",0,"reason","演示配置原因"))).path("id").asLong();}
    private long room(Browser admin,long building)throws Exception{return ok(admin.send("POST","/api/admin/housing/rooms",Map.of("buildingId",building,"code","R-"+UUID.randomUUID().toString().substring(0,8),"name","201","floorLabel","2 层","version",0,"reason","演示配置原因"))).path("id").asLong();}
    private long bed(Browser admin,long room,String code)throws Exception{String label="B".equals(code)?"2 号床":"1 号床";return ok(admin.send("POST","/api/admin/housing/beds",Map.of("roomId",room,"code",code,"label",label,"version",0,"reason","演示配置原因"))).path("id").asLong();}
    private HashMap<String,Object> draft(){return new HashMap<>(Map.of("requestKey",key(),"version",0,"reason","希望换到 quieter 的楼层","preferredArea","东校区低楼层","materialNote","已准备年级证明"));}
    private Map<String,Object> action(int version,String comment){return Map.of("requestKey",key(),"version",version,"comment",comment);}
    private Map<String,Object> reserve(int version,long bed){return Map.of("requestKey",key(),"version",version,"bedId",bed,"reason","安排空闲床位");}
    private String key(){return "HT"+UUID.randomUUID().toString().replace("-","");}
    private String student(){return account("ht","调宿测试学生","20260001","STUDENT");}
    private String account(String prefix,String label,String source,String role){String name=prefix+UUID.randomUUID().toString().replace("-","").substring(0,12);jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :u,password_hash,:n,:num,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username=:source").param("u",name).param("n",label).param("num",prefix.toUpperCase()+"-"+name).param("source",source).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)").param("id",user(name)).param("role",role).update();return name;}
    private String usernameOf(Browser ignored){return jdbc.sql("SELECT username FROM app_user WHERE full_name='其他宿管' ORDER BY id DESC").query(String.class).list().get(0);}
    private long user(String name){return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private HttpResponse<String> sendUnchecked(Browser actor,String method,String path,Map<String,?> body){try{return actor.send(method,path,body);}catch(Exception ex){throw new RuntimeException(ex);}}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private class Browser{
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
