────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
Using deepseek model with API key from environment.
Aider v0.86.2
Model: deepseek/deepseek-chat with diff edit format, prompt cache, infinite output
Git repo: .git with 527 files
Repo-map: using 4096 tokens, auto refresh
Here are summaries of some files present in my git repository.
Do not propose changes to these files, treat them as *read-only*.
If you need to edit any of these files, ask me to *add them to the chat* first.

agent-service/app/harness/json_guard.py:
⋮
│class AIJsonGuardError(AgentServiceError):
⋮
│def parse(raw_text: str) -> LLMDecomposeResponse:
⋮

agent-service/app/harness/sse_stream.py:
⋮
│def format_sse(event: str, data: dict[str, Any]) -> str:
⋮

agent-service/app/main.py:
⋮
│def create_app() -> FastAPI:
⋮

agent-service/app/models/api_schemas.py:
⋮
│class ChatStreamRequest(BaseModel):
⋮
│class AiDecomposeRequest(BaseModel):
⋮
│class SummaryOneFamilyRequest(BaseModel):
⋮

agent-service/app/models/diff_types.py:
⋮
│class State(StrEnum):
⋮

agent-service/app/models/llm_schemas.py:
⋮
│class SubtaskProposal(BaseModel):
⋮
│class LLMDecomposeResponse(BaseModel):
⋮

agent-service/app/services/auth_verifier.py:
⋮
│@dataclass(frozen=True)
│class CurrentUser:
⋮

agent-service/app/settings.py:
⋮
│class Settings(BaseSettings):
⋮
│@lru_cache
│def get_settings() -> Settings:
⋮

agent-service/tests/test_connectors.py:
⋮
│async def test_weather_ok_and_degrade_to_unknown() -> None:
│    def ok(request: httpx.Request) -> httpx.Response:
│        assert request.url.params["city"] == "杭州"
⋮

backend/src/main/java/com/familyapp/agent/AgentApiDtos.java:
⋮
│public final class AgentApiDtos {
│
│    private AgentApiDtos() {
│    }
│
│    // ==================== 请求 ====================
│
│    /** C-3 创建任务请求。source 强制为 agent，不接受外部指定。 */
│    public record TaskCreateReq(
│            String title,
⋮

backend/src/main/java/com/familyapp/agent/AgentCallbackClient.java:
⋮
│@Component
│public class AgentCallbackClient {
│
⋮
│    private static RestTemplate buildTemplate(int readTimeoutMs) {
│        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
│        factory.setConnectTimeout(5000);
│        factory.setReadTimeout(readTimeoutMs);
│        return new RestTemplate(factory);
⋮

backend/src/main/java/com/familyapp/agent/DecomposeSession.java:
⋮
│@Entity
│@Table(name = "agent_decompose_sessions")
│public class DecomposeSession {
│
⋮
│    @PreUpdate
│    protected void onUpdate() {
⋮
│    public String getInitTaskTitle() { return initTaskTitle; }
│    public void setInitTaskTitle(String initTaskTitle) { this.initTaskTitle = initTaskTitle; }
│    public Long getInitTaskCategoryId() { return initTaskCategoryId; }
│    public void setInitTaskCategoryId(Long initTaskCategoryId) { this.initTaskCategoryId = initTask
│    public String getInitTaskDescription() { return initTaskDescription; }
│    public void setInitTaskDescription(String initTaskDescription) { this.initTaskDescription = ini
│    public String getInitTaskPriority() { return initTaskPriority; }
│    public void setInitTaskPriority(String initTaskPriority) { this.initTaskPriority = initTaskPrio
│    public LocalDate getInitTaskDueDate() { return initTaskDueDate; }
│    public void setInitTaskDueDate(LocalDate initTaskDueDate) { this.initTaskDueDate = initTaskDueD
│    public Long getInitTaskId() { return initTaskId; }
⋮
│    public List<Object> getMessages() { return messages; }
⋮

backend/src/main/java/com/familyapp/agent/RuleTemplate.java:
⋮
│@Entity
│@Table(name = "rule_templates")
│public class RuleTemplate {
│
⋮
│    @PreUpdate
│    protected void onUpdate() {
⋮
│    public java.util.List<String> getProfileTypes() { return profileTypes; }
│    public void setProfileTypes(java.util.List<String> profileTypes) { this.profileTypes = profileT
⋮

backend/src/main/java/com/familyapp/agent/RuleTemplateVersion.java:
⋮
│@Entity
│@Table(name = "rule_template_versions")
│public class RuleTemplateVersion {
│
⋮
│    public Long getTemplateId() { return templateId; }
⋮

backend/src/main/java/com/familyapp/agent/Subtask.java:
⋮
│@Entity
│@Table(name = "subtasks")
│public class Subtask {
│
⋮
│    @PreUpdate
│    protected void onUpdate() {
⋮

backend/src/main/java/com/familyapp/agent/SummaryExecutionLog.java:
⋮
│@Entity
│@Table(name = "summary_execution_log")
│public class SummaryExecutionLog {
│
⋮
│    public void setRunAt(Instant runAt) { this.runAt = runAt; }
⋮

backend/src/main/java/com/familyapp/agent/TaskSchedule.java:
⋮
│@Entity
│@Table(name = "task_schedules")
│public class TaskSchedule {
│
⋮
│    public void setIsExecuted(Boolean isExecuted) { this.isExecuted = isExecuted; }
⋮

backend/src/main/java/com/familyapp/agent/TaskTrigger.java:
⋮
│@Entity
│@Table(name = "task_triggers")
│public class TaskTrigger {
│
⋮
│    public Long getSourceTaskId() { return sourceTaskId; }
│    public void setSourceTaskId(Long sourceTaskId) { this.sourceTaskId = sourceTaskId; }
│    public String getTriggerType() { return triggerType; }
│    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
⋮

backend/src/main/java/com/familyapp/auth/UserContext.java:
⋮
│public final class UserContext {
│
│    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();
│
│    private UserContext() {}
│
│    /**
│     * 当前登录用户信息。userId 必填，familyId/role 在 AuthInterceptor 阶段尚不可知，
│     * 后续 X-Family-Id 拦截器或 Controller 层会按需补齐。
│     */
⋮
│    public static void clear() {
│        HOLDER.remove();
⋮

backend/src/main/java/com/familyapp/category/Category.java:
⋮
│@Entity
│@Table(name = "categories")
│@Where(clause = "deleted_at IS NULL")
│public class Category {
│
⋮
│    @PreUpdate
│    protected void onUpdate() {
⋮

backend/src/main/java/com/familyapp/category/CategoryController.java:
⋮
│@RestController
│@RequestMapping("/api/categories")
│public class CategoryController {
│
⋮
│    @GetMapping("/{id}")
│    public Result<CategoryResponse> get(@PathVariable Long id) {
⋮

backend/src/main/java/com/familyapp/category/CategoryService.java:
⋮
│public interface CategoryService {
│    List<CategoryResponse> list();
│    CategoryResponse get(Long id);
│    CategoryResponse create(CreateCategoryRequest req);
│    CategoryResponse update(Long id, UpdateCategoryRequest req);
│    void delete(Long id, Long mergeTo);
│    void merge(Long sourceId, MergeRequest req);
⋮

backend/src/main/java/com/familyapp/category/CategoryServiceImpl.java:
⋮
│@Service
│@Transactional
│public class CategoryServiceImpl implements CategoryService {
│
⋮
│    @Override
│    @Transactional(readOnly = true)
│    public CategoryResponse get(Long id) {
⋮

backend/src/main/java/com/familyapp/category/dto/MergeRequest.java:
⋮
│public class MergeRequest {
│
│    @NotNull(message = "mergeTo is required")
│    private Long mergeTo;
│
│    public Long getMergeTo() { return mergeTo; }
│    public void setMergeTo(Long mergeTo) { this.mergeTo = mergeTo; }
⋮

backend/src/main/java/com/familyapp/common/ApiException.java:
⋮
│public class ApiException extends RuntimeException {
│    private final ResultCode code;
│
│    public ApiException(ResultCode code) {
│        super(code.getMsg());
│        this.code = code;
│    }
│
│    public ApiException(ResultCode code, String customMessage) {
│        super(customMessage);
⋮

backend/src/main/java/com/familyapp/common/Result.java:
⋮
│public class Result<T> {
│    private int code;
│    private String msg;
│    private T data;
│
│    public Result() {}
│
│    public Result(int code, String msg, T data) {
│        this.code = code;
│        this.msg = msg;
⋮
│    public static <T> Result<T> ok(T data) {
│        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), data);
⋮
│    public static Result<Void> ok() {
│        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), null);
⋮
│    public String getMsg() { return msg; }
⋮

backend/src/main/java/com/familyapp/common/ResultCode.java:
⋮
│public enum ResultCode {
│
│    // ========== 通用 ==========
│    OK(0, HttpStatus.OK, "操作成功"),
│    BAD_REQUEST_VALIDATION(40099, HttpStatus.BAD_REQUEST, "参数校验失败"),
│    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),
│    SERVER_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试"),
│
│    // ========== 任务相关 ==========
│    BAD_REQUEST_TITLE_EMPTY(40001, HttpStatus.BAD_REQUEST, "任务标题不能为空"),
⋮
│    ASSIGNEE_PROFILE_ID_CONFLICT(41008, HttpStatus.BAD_REQUEST, "指派给谁参数冲突，请仅使用新档案字段");
│
⋮
│    public HttpStatus getHttpStatus() { return httpStatus; }
│    public String getMsg() { return msg; }
⋮

backend/src/main/java/com/familyapp/config/ClockConfig.java:
⋮
│@Configuration
│public class ClockConfig {
│
│    @Bean
│    public Clock clock() {
⋮

backend/src/main/java/com/familyapp/config/SecurityConfig.java:
⋮
│@Configuration
│public class SecurityConfig {
│
⋮
│    @Bean
│    public PasswordEncoder passwordEncoder() {
⋮

backend/src/main/java/com/familyapp/config/WebConfig.java:
⋮
│@Configuration
│@ConditionalOnProperty(name = "familyapp.auth.enabled", havingValue = "true", matchIfMissing = true
│public class WebConfig implements WebMvcConfigurer {
│
⋮
│    @Override
│    public void addInterceptors(InterceptorRegistry registry) {
⋮

backend/src/main/java/com/familyapp/family/FamilyController.java:
⋮
│@RestController
│@RequestMapping("/api/families")
│public class FamilyController {
│
⋮
│    @GetMapping("/{id}")
│    public Result<FamilyResponse> get(@PathVariable Long id) {
⋮

backend/src/main/java/com/familyapp/family/FamilyMember.java:
⋮
│@Entity
│@Table(name = "family_members")
│public class FamilyMember {
│
⋮

backend/src/main/java/com/familyapp/family/FamilyRole.java:
⋮
│public enum FamilyRole {
│    /** 家庭管理员（创建者）：拥有最高权限，可转让身份、踢人、解散家庭。 */
│    OWNER,
│    /** 成人成员：可创建/编辑任务、分类、邀请成员。 */
│    ADULT,
│    /** 孩子成员：只读为主，仅能操作自己创建或分配给自己的任务。 */
│    CHILD;
│
│    /**
│     * 返回中文角色名（用于登录响应 roleName 字段，适配老人显示）。
│     */
│    public String getChineseName() {
│        return switch (this) {
│            case OWNER -> "家庭管理员";
│            case ADULT -> "成人成员";
│            case CHILD -> "孩子成员";
│        };
⋮

backend/src/main/java/com/familyapp/family/InviteRepository.java:
⋮
│@Repository
│public interface InviteRepository extends JpaRepository<Invite, Long> {
│
⋮
│    Optional<Invite> findByCodeAndStatus(String code, InviteStatus status);
│
⋮

backend/src/main/java/com/familyapp/family/dto/AcceptInviteRequest.java:
⋮
│public class AcceptInviteRequest {
│
│    @NotBlank(message = "邀请码不能为空")
│    @Size(min = 6, max = 6, message = "邀请码必须是 6 位")
│    private String code;
│
│    public String getCode() { return code; }
│    public void setCode(String code) { this.code = code; }
⋮

backend/src/main/java/com/familyapp/family/dto/ChangeRoleRequest.java:
⋮
│public class ChangeRoleRequest {
│
│    @NotBlank(message = "角色不能为空")
│    private String role;
│
│    public String getRole() { return role; }
│    public void setRole(String role) { this.role = role; }
⋮

backend/src/main/java/com/familyapp/family/dto/TransferOwnerRequest.java:
⋮
│public class TransferOwnerRequest {
│
│    @NotNull(message = "请指定新管理员")
│    private Long targetUserId;
│
│    public Long getTargetUserId() { return targetUserId; }
│    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
⋮

backend/src/main/java/com/familyapp/profile/PersonProfile.java:
⋮
│@Entity
│@Table(name = "person_profiles")
│@Where(clause = "deleted_at IS NULL")
│public class PersonProfile {
│
⋮
│    @PreUpdate
│    protected void onUpdate() {
⋮

backend/src/main/java/com/familyapp/profile/PersonProfileController.java:
⋮
│@RestController
│@RequestMapping("/api/v1/person-profiles")
│public class PersonProfileController {
│
⋮
│    @GetMapping("/{id}")
│    public Result<PersonProfileResponse> get(@PathVariable Long id) {
⋮

backend/src/main/java/com/familyapp/profile/PersonProfileService.java:
⋮
│public interface PersonProfileService {
│
│    /** P-1 GET / 列档案（profileType 可空，空=全类型） */
│    List<PersonProfileResponse> list(String profileType);
│
│    /** P-3 GET /{id} 详情 */
│    PersonProfileResponse get(Long id);
│
│    /** P-2 POST 创建档案（Service 层兜底 avatar_emoji） */
│    PersonProfileResponse create(CreatePersonProfileRequest req);
│
⋮

backend/src/main/java/com/familyapp/profile/PersonProfileServiceImpl.java:
⋮
│@Service
│@Transactional
│public class PersonProfileServiceImpl implements PersonProfileService {
│
⋮
│    @Override
│    @Transactional(readOnly = true)
│    public PersonProfileResponse get(Long id) {
⋮

backend/src/main/java/com/familyapp/profile/ProfileDataSchema.java:
⋮
│public class ProfileDataSchema {
│
│    private final ObjectMapper mapper = new ObjectMapper();
│    private final Map<String, Map<String, FieldSpec>> schema = new HashMap<>();
│
│    public ProfileDataSchema() {
│        try (InputStream is = new ClassPathResource("schemas/profile-schema.json").getInputStream()
│            @SuppressWarnings("unchecked")
│            Map<String, Map<String, Object>> raw = mapper.readValue(is, Map.class);
│            for (Map.Entry<String, Map<String, Object>> typeEntry : raw.entrySet()) {
⋮
│    private static class FieldSpec {
│        final String type;
│        final List<Integer> range;
│        final List<String> values;
│
│        FieldSpec(String type, List<Integer> range, List<String> values) {
│            this.type = type;
│            this.range = range;
│            this.values = values;
│        }
│
⋮
│        String checkType(Object value) {
│            switch (type) {
│                case "number":
│                    if (!(value instanceof Number)) {
│                        return "type_mismatch_expect_number";
│                    }
│                    if (range != null) {
│                        double v = ((Number) value).doubleValue();
│                        if (v < range.get(0) || v > range.get(1)) {
│                            return "range_exceeded";
⋮

backend/src/main/java/com/familyapp/profile/dto/UpdatePersonProfileRequest.java:
⋮
│public class UpdatePersonProfileRequest {
│    private Map<String, Object> profileDataPatch;
│    private List<String> removeKeys;
│
│    public Map<String, Object> getProfileDataPatch() { return profileDataPatch; }
│    public void setProfileDataPatch(Map<String, Object> profileDataPatch) { this.profileDataPatch =
│    public List<String> getRemoveKeys() { return removeKeys; }
│    public void setRemoveKeys(List<String> removeKeys) { this.removeKeys = removeKeys; }
⋮

backend/src/main/java/com/familyapp/task/Task.java:
⋮
│@Entity
│@Table(name = "tasks")
│@Where(clause = "deleted_at IS NULL")
│public class Task {
│
⋮
│    @PreUpdate
│    protected void onUpdate() {
⋮
│    public Long getTargetTemplateVersionId() { return targetTemplateVersionId; }
⋮

backend/src/main/java/com/familyapp/task/TaskController.java:
⋮
│@RestController
│@RequestMapping("/api/tasks")
│public class TaskController {
│
⋮
│    @GetMapping("/{id}")
│    public Result<TaskResponse> get(@PathVariable Long id) {
⋮

backend/src/main/java/com/familyapp/task/TaskService.java:
⋮
│public interface TaskService {
│    TaskResponse create(CreateTaskRequest req);
│    /**
│     * 过渡方法：返回底层 Task 实体（InternalAgent 端点/单元测试直接访问 assigneeProfileId 等原始字段）。
│     * 内部逻辑与 create(req) 完全一致。
│     */
│    Task createTask(CreateTaskRequest req);
│    List<TaskResponse> list(TaskFilter filter);
│    TaskResponse get(Long id);
│    TaskResponse update(Long id, UpdateTaskRequest req);
⋮

backend/src/main/java/com/familyapp/task/TaskServiceImpl.java:
⋮
│@Service
│@Transactional
│public class TaskServiceImpl implements TaskService {
│
⋮
│    @Override
│    @Transactional(readOnly = true)
│    public TaskResponse get(Long id) {
⋮

backend/src/main/java/com/familyapp/wechat/WechatBind.java:
⋮
│@Entity
│@Table(
│    name = "wechat_bind",
│    uniqueConstraints = @UniqueConstraint(
│        name = "uq_wechat_bind_openid",
│        columnNames = "openid"),
│    indexes = @Index(name = "idx_wechat_bind_user", columnList = "user_id")
│)
│public class WechatBind {
│
⋮
│    public Instant getBoundAt() {
│        return boundAt;
⋮

backend/src/main/java/com/familyapp/wechat/WechatNotifyClient.java:
⋮
│@Component
│public class WechatNotifyClient {
│
⋮
│    private static RestTemplate buildTemplate() {
│        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
│        factory.setConnectTimeout(2000);
│        factory.setReadTimeout(5000);
│        return new RestTemplate(factory);
⋮

backend/src/main/java/com/familyapp/wechat/WechatNotifyDtos.java:
⋮
│public final class WechatNotifyDtos {
│
│    private WechatNotifyDtos() {
│    }
│
│    /** 单条消息：openid + 事件类型 + 业务参数（渲染细节在 wechat-service）。 */
│    public record WechatNotifyMessage(String openid, String event, Map<String, Object> params) {
│    }
│
│    /** 批量请求体。 */
⋮

backend/src/test/java/com/familyapp/FamilyAppApplicationTest.java:
⋮
│@SpringBootTest
│@ActiveProfiles("test")
│class FamilyAppApplicationTest {
│
│    @Test
│    void contextLoads() {
⋮

backend/src/test/java/com/familyapp/V9MigrationTest.java:
⋮
│@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
│@ActiveProfiles("test")
│@Transactional
│@Sql(scripts = {
│    // 1. 预置演示家庭种子数据（family_id=1 + user_id=1 + 4 分类），供 V9 FK 引用
│    //    复用 V6 的 v6-test-setup.sql（V9 同样需要这些基础数据，不重复造轮子）
│    "classpath:db/v6-test-setup.sql",
│    // 2. 运行 V9 迁移脚本（2 表 DDL + 2 列 ALTER + 索引）
│    //    V9.sql 不存在时此步失败 = TDD RED 阶段；存在且通过 = GREEN 阶段
│    "classpath:db/migration/V9__Add_Person_Profiles_And_Decompose_Sessions.sql"
│})
│class V9MigrationTest {
│
⋮
│    @Test
│    void 验证两张新表的NOT_NULL约束列正确() {
⋮

backend/src/test/java/com/familyapp/common/PasswordEncoderTest.java:
⋮
│class PasswordEncoderTest {
│
│    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();
│
│    /** V4 SQL 种子数据的演示用户明文密码。 */
│    private static final String DEMO_PASSWORD = "familyapp123";
│
│    /**
│     * 将此处替换为本测试第一次执行时 stdout 打印的 hash 值，
│     * 与 V4 SQL 中的字面量保持一致。
⋮
│    @Test
│    void generateDemoPasswordHashAndVerifyV4SqlLiteral() {
⋮

frontend/src/api/client.js:
⋮
│function showError(msg) {
│  ElMessage({
│    type: 'error',
│    message: msg,
│    duration: 5000,
│    showClose: true,
│  })
⋮

frontend/src/api/tasks.js:
⋮
│export const tasksApi = {
│  list:         ()              => client.get('/tasks'),
│  create:       (payload)       => client.post('/tasks', payload),
│  get:          (id)            => client.get(`/tasks/${id}`),
│  update:       (id, payload)   => client.put(`/tasks/${id}`, payload),
│  del:          (id)            => client.delete(`/tasks/${id}`),
│  updateStatus: (id, status)    => client.post(`/tasks/${id}/status`, { status })
⋮

frontend/src/stores/auth.js:
⋮
│function parseJSON(value, fallback) {
│  if (!value) return fallback
│  try {
│    return JSON.parse(value)
│  } catch (e) {
│    return fallback
│  }
⋮

frontend/src/stores/decompose.js:
⋮
│export const useDecomposeStore = defineStore('decompose', {
│  state: () => ({
│    // null = 未加载；true/false = 已从 /config 读到开关状态
│    aiDecomposeEnabled: null,
│    loaded: false
│  }),
│  getters: {
│    // 是否已明确关闭（前端用它决定是否渲染 AI 入口）
│    disabled: (state) => state.loaded && state.aiDecomposeEnabled === false
│  },
│  actions: {
│    // 应用启动时调用：读 /config 并同步 body class
│    async init() {
│      try {
│        const data = await client.get('/agent/v1/config')
│        // client 响应拦截器已剥外壳，data 即 { ai_decompose_enabled: bool }
│        this.aiDecomposeEnabled = !!(data && data.ai_decompose_enabled)
│      } catch (e) {
│        // /config 调用失败：保守视为关闭（隐藏 AI 入口，避免老人误触报错）
│        this.aiDecomposeEnabled = false
⋮
│    applyBodyClass() {
│      if (typeof document === 'undefined') return
│      if (this.aiDecomposeEnabled === false) {
│        document.body.classList.add('ai-disabled')
│      } else {
│        document.body.classList.remove('ai-disabled')
│      }
⋮

frontend/src/stores/family.js:
⋮
│function parseJSON(value, fallback) {
│  if (!value) return fallback
│  try {
│    return JSON.parse(value)
│  } catch (e) {
│    return fallback
│  }
⋮
│export const useFamilyStore = defineStore('family', {
│  state: () => ({
│    currentFamily: parseJSON(localStorage.getItem('currentFamily'), null),
│    members: []
│  }),
│  getters: {
│    currentFamilyId: (state) => {
│      if (state.currentFamily && state.currentFamily.id) {
│        return String(state.currentFamily.id)
│      }
⋮
│  actions: {
│    setCurrent(family) {
│      this.currentFamily = family || null
│      if (family && family.id) {
│        localStorage.setItem('currentFamilyId', String(family.id))
│        localStorage.setItem('currentFamily', JSON.stringify(family))
│      } else {
│        localStorage.removeItem('currentFamilyId')
│        localStorage.removeItem('currentFamily')
│      }
⋮
│    clear() {
│      this.currentFamily = null
│      this.members = []
│      localStorage.removeItem('currentFamilyId')
│      localStorage.removeItem('currentFamily')
⋮

frontend/src/stores/font.js:
⋮
│function readScaleFromStorage() {
│  const raw = (typeof localStorage !== 'undefined') ? localStorage.getItem(STORAGE_KEY) : null
│  if (raw && SCALE_ENUM.includes(raw)) return raw
│  return DEFAULT_SCALE
⋮
│function applyHtmlClass(scale) {
│  if (typeof document === 'undefined') return
│  const html = document.documentElement
│  html.classList.remove('font-scale-s', 'font-scale-m', 'font-scale-l')
│  html.classList.add('font-scale-' + (scale || 'm').toLowerCase())
⋮

frontend/src/tests/api/client.test.js:
⋮
│describe('api client mock 层', () => {
│  beforeEach(() => {
│    localStorage.clear()
│    vi.clearAllMocks()
│  })
│
│  it('1. baseURL 常量实际值以 /api 开头', () => {
│    expect(client.defaults.baseURL).toBe('/api')
│    expect(client.defaults.baseURL.startsWith('/api')).toBe(true)
│  })
│
⋮
│  it('8. response interceptor 对 { code != 0, msg } 的错误包装消息包含 code+msg', () => {
│    let onFulfilled = null
│    let onRejected = null
│    const originalMock = vi.mocked(client.interceptors.response)
│    originalMock.use = (fulfilled, rejected) => {
│      onFulfilled = fulfilled
│      onRejected = rejected
│    }
│    client.interceptors.response.use = originalMock.use
│
⋮
│    const fakeInstance = {
│      defaults: { baseURL: '/api' },
│      interceptors: {
│        request: { use: (fn) => { requestUse = fn } },
│        response: { use: (f, r) => { onFulfilled = f; onRejected = r } }
│      },
│      get: () => {}, post: () => {}, put: () => {}, delete: () => {}
⋮

frontend/src/tests/components/BottomTabBar.test.js:
⋮
│function mountWithRouter(initialPath = '/tasks') {
│  const router = createRouter({
│    history: createMemoryHistory(),
│    routes: [
│      { path: '/dashboard', component: { template: '<div/>' } },
│      { path: '/tasks', component: { template: '<div/>' } },
│      { path: '/chat', component: { template: '<div/>' } },
│      { path: '/more', component: { template: '<div/>' } }
│    ]
│  });
⋮

frontend/src/tests/components/TaskCardMobile.test.js:
⋮
│function mountCard(task) {
│  return mount(TaskCardMobile, {
│    props: { task },
│    global: {
│      config: { globalProperties: {} }
│    }
│  });
⋮

frontend/src/tests/composables/useIsMobile.test.js:
⋮
│function mockMatchMedia(initialMatches) {
│  const listeners = new Set();
│  const mql = {
│    matches: initialMatches,
│    media: '(max-width: 768px)',
│    addEventListener: (_ev, cb) => listeners.add(cb),
│    removeEventListener: (_ev, cb) => listeners.delete(cb),
│    dispatchChange(matches) {
│      mql.matches = matches;
│      listeners.forEach(cb => cb({ matches }));
⋮

frontend/src/tests/layouts/AppLayoutResponsive.test.js:
⋮
│function mockMatchMedia(matches) {
│  const listeners = new Set();
│  const mql = {
│    matches,
│    addEventListener: (_ev, cb) => listeners.add(cb),
│    removeEventListener: (_ev, cb) => listeners.delete(cb),
│    dispatchChange(m) { mql.matches = m; listeners.forEach(cb => cb({ matches: m })); }
│  };
│  window.matchMedia = vi.fn(() => mql);
│  return mql;
⋮

frontend/src/tests/views/MoreMenu.test.js:
⋮
│function mountWithRouter() {
│  const router = createRouter({
│    history: createMemoryHistory(),
│    routes: [
│      { path: '/more', component: MoreMenuView },
│      { path: '/templates', component: { template: '<div/>' } },
│      { path: '/automation', component: { template: '<div/>' } },
│      { path: '/categories', component: { template: '<div/>' } },
│      { path: '/families', component: { template: '<div/>' } }
│    ]
⋮

frontend/src/tests/views/TaskViewMobile.test.js:
⋮
│function mockMatchMedia(matches) {
│  const listeners = new Set();
│  const mql = {
│    matches,
│    addEventListener: (_ev, cb) => listeners.add(cb),
│    removeEventListener: (_ev, cb) => listeners.delete(cb)
│  };
│  window.matchMedia = vi.fn(() => mql);
│  return mql;
⋮

frontend/src/utils/profileRender.js:
⋮
│const mkStrArr = (key,label,suggests=[]) => ({ key, labelZh:label, component:'el-select-multiple', 
⋮

frontend/src/utils/shareCard.js:
⋮
│function roundRect(ctx, x, y, w, h, r, fill, stroke, lineWidth = 1) {
│  const radius = typeof r === 'number' ? { tl: r, tr: r, br: r, bl: r } : { tl: 0, tr: 0, br: 0, bl
│  ctx.beginPath()
│  ctx.moveTo(x + radius.tl, y)
│  ctx.lineTo(x + w - radius.tr, y)
│  ctx.quadraticCurveTo(x + w, y, x + w, y + radius.tr)
│  ctx.lineTo(x + w, y + h - radius.br)
│  ctx.quadraticCurveTo(x + w, y + h, x + w - radius.br, y + h)
│  ctx.lineTo(x + radius.bl, y + h)
│  ctx.quadraticCurveTo(x, y + h, x, y + h - radius.bl)
⋮

frontend/src/utils/sseReconnect.js:
⋮
│export function createReconnectState() {
│  return { delay: BASE_DELAY, failCount: 0, slowMode: false };
⋮
│export function nextDelay(state, event) {
│  const { connected = false } = event || {};
│  if (connected) {
│    return { delay: BASE_DELAY, failCount: 0, slowMode: false };
│  }
│  const nextFailCount = state.failCount + 1;
│  if (state.slowMode) {
│    // 慢模式：刚进入慢模式的第1次失败（failCount 从 3→4）给 SLOW_MODE_DELAY
│    // 之后（failCount>=5）稳定在 MAX_DELAY，符合用例4 "cap at MAX" 期望
│    if (nextFailCount === 4) {
⋮
│export function forceReconnectNow(state) {
│  return { delay: BASE_DELAY, failCount: 0, slowMode: false };
⋮

tool-service/app/main.py:
⋮
│def create_app() -> FastAPI:
⋮

tool-service/app/models/weather_schemas.py:
⋮
│class WeatherType(StrEnum):
⋮
│class WeatherResponse(BaseModel):
⋮

tool-service/app/services/lru_cache.py:
⋮
│class TTLLRUCache[K, V]:
│    """泛型 TTL+LRU 缓存。get 自动检查过期并淘汰；set 满容量时淘汰最老项。"""
│
⋮
│    def get(self, key: K) -> V | None:
⋮

tool-service/app/settings.py:
⋮
│class Settings(BaseSettings):
⋮
│@lru_cache
│def get_settings() -> Settings:
⋮

wechat-service/app/main.py:
⋮
│def create_app(
│    settings: Settings | None = None,
│    backend: BackendClient | None = None,
│    wechat_client: httpx.AsyncClient | None = None,
⋮

wechat-service/app/settings.py:
⋮
│class Settings(BaseSettings):
⋮
│@lru_cache
│def get_settings() -> Settings:
⋮

wechat-service/app/wechat/oauth.py:
⋮
│class WechatApiError(RuntimeError):
⋮

wechat-service/app/wechat/signature.py:
⋮
│def calc_signature(token: str, timestamp: str, nonce: str) -> str:
⋮

wechat-service/app/wechat/template.py:
⋮
│@dataclass
│class SendResult:
⋮

wechat-service/tests/conftest.py:
⋮
│@pytest.fixture
│async def asgi_factory() -> Callable[..., httpx.AsyncClient]:
⋮

