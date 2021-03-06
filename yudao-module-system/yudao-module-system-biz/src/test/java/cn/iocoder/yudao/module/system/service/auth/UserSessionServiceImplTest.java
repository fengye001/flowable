package cn.iocoder.yudao.module.system.service.auth;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.date.DateUtils;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbAndRedisUnitTest;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.session.UserSessionPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.auth.UserSessionDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.auth.UserSessionMapper;
import cn.iocoder.yudao.module.system.dal.redis.auth.LoginUserRedisDAO;
import cn.iocoder.yudao.module.system.enums.common.SexEnum;
import cn.iocoder.yudao.module.system.enums.logger.LoginLogTypeEnum;
import cn.iocoder.yudao.module.system.enums.logger.LoginResultEnum;
import cn.iocoder.yudao.module.system.service.logger.LoginLogService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Calendar;

import static cn.hutool.core.util.RandomUtil.randomEle;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.addTime;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.*;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserSessionServiceImpl} ???????????????
 *
 * @author Lyon
 */
@Import({UserSessionServiceImpl.class, LoginUserRedisDAO.class})
public class UserSessionServiceImplTest extends BaseDbAndRedisUnitTest {

    @Resource
    private UserSessionServiceImpl userSessionService;

    @Resource
    private UserSessionMapper userSessionMapper;

    @MockBean
    private AdminUserService userService;
    @MockBean
    private LoginLogService loginLogService;
    @Resource
    private LoginUserRedisDAO loginUserRedisDAO;

    @MockBean
    private SecurityProperties securityProperties;

    @BeforeEach
    public void setUp() {
        when(securityProperties.getSessionTimeout()).thenReturn(Duration.ofDays(1L));
    }

    @Test
    public void testGetUserSessionPage_success() {
        // mock ??????
        AdminUserDO dbUser = randomPojo(AdminUserDO.class, o -> {
            o.setSex(randomEle(SexEnum.values()).getSex());
            o.setStatus(CommonStatusEnum.ENABLE.getStatus());
        });
        when(userService.getUsersByUsername(eq(dbUser.getUsername()))).thenReturn(singletonList(dbUser));
        // ??????????????????????????????
        String userIp = randomString();
        UserSessionDO dbSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserId(dbUser.getId());
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setUserIp(userIp);
        });
        userSessionMapper.insert(dbSession);
        // ?????? username ?????????
        userSessionMapper.insert(ObjectUtils.cloneIgnoreId(dbSession, o -> o.setUserId(123456L)));
        // ?????? userIp ?????????
        userSessionMapper.insert(ObjectUtils.cloneIgnoreId(dbSession, o -> o.setUserIp("testUserIp")));
        // ????????????
        UserSessionPageReqVO reqVO = new UserSessionPageReqVO();
        reqVO.setUsername(dbUser.getUsername());
        reqVO.setUserIp(userIp);

        // ??????
        PageResult<UserSessionDO> pageResult = userSessionService.getUserSessionPage(reqVO);
        // ??????
        assertEquals(1, pageResult.getTotal());
        assertEquals(1, pageResult.getList().size());
        assertPojoEquals(dbSession, pageResult.getList().get(0));
    }

    @Test
    public void testClearSessionTimeout_none() {
        // mock db ??????
        UserSessionDO userSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setSessionTimeout(addTime(Duration.ofDays(1)));
        });
        userSessionMapper.insert(userSession);

        // ??????
        long count = userSessionService.deleteTimeoutSession();
        // ??????
        assertEquals(0, count);
        assertPojoEquals(userSession, userSessionMapper.selectById(userSession.getId())); // ?????????
    }

    @Test // Redis ??????????????????
    public void testClearSessionTimeout_exists() {
        // mock db ??????
        UserSessionDO userSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setSessionTimeout(DateUtils.addDate(Calendar.DAY_OF_YEAR, -1));
        });
        userSessionMapper.insert(userSession);
        // mock redis ??????
        loginUserRedisDAO.set(userSession.getToken(), new LoginUser());

        // ??????
        long count = userSessionService.deleteTimeoutSession();
        // ??????
        assertEquals(0, count);
        assertPojoEquals(userSession, userSessionMapper.selectById(userSession.getId())); // ?????????
    }

    @Test
    public void testClearSessionTimeout_success() {
        // mock db ??????
        UserSessionDO userSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setSessionTimeout(DateUtils.addDate(Calendar.DAY_OF_YEAR, -1));
        });
        userSessionMapper.insert(userSession);

        // ??????????????????
        long count = userSessionService.deleteTimeoutSession();
        // ??????
        assertEquals(1, count);
        assertNull(userSessionMapper.selectById(userSession.getId())); // ?????????
        verify(loginLogService).createLoginLog(argThat(loginLog -> {
            assertPojoEquals(userSession, loginLog);
            assertEquals(LoginLogTypeEnum.LOGOUT_TIMEOUT.getType(), loginLog.getLogType());
            assertEquals(LoginResultEnum.SUCCESS.getResult(), loginLog.getResult());
            return true;
        }));
    }

    @Test
    public void testCreateUserSession_success() {
        // ????????????
        String userIp = randomString();
        String userAgent = randomString();
        LoginUser loginUser = randomPojo(LoginUser.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setTenantId(0L); // ??????????????? 0????????????????????????????????????
        });

        // ??????
        String token = userSessionService.createUserSession(loginUser, userIp, userAgent);
        // ?????? UserSessionDO ??????
        UserSessionDO userSessionDO = userSessionMapper.selectOne(UserSessionDO::getToken, token);
        assertPojoEquals(loginUser, userSessionDO, "id", "updateTime");
        assertEquals(token, userSessionDO.getToken());
        assertEquals(userIp, userSessionDO.getUserIp());
        assertEquals(userAgent, userSessionDO.getUserAgent());
        // ?????? LoginUser ??????
        LoginUser redisLoginUser = loginUserRedisDAO.get(token);
        assertPojoEquals(loginUser, redisLoginUser, "username", "password");
    }

    @Test
    public void testCreateRefreshUserSession() {
        // ????????????
        String token = randomString();

        // mock redis ??????
        LoginUser loginUser = randomPojo(LoginUser.class, o -> o.setUserType(randomEle(UserTypeEnum.values()).getValue()));
        loginUserRedisDAO.set(token, loginUser);
        // mock db ??????
        UserSessionDO userSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setToken(token);
        });
        userSessionMapper.insert(userSession);

        // ??????
        userSessionService.refreshUserSession(token, loginUser);
        // ?????? LoginUser ??????
        LoginUser redisLoginUser = loginUserRedisDAO.get(token);
        assertPojoEquals(redisLoginUser, loginUser, "username", "password");
        // ?????? UserSessionDO ??????
        UserSessionDO updateDO = userSessionMapper.selectOne(UserSessionDO::getToken, token);
        assertEquals(updateDO.getUsername(), loginUser.getUsername());
        assertNotNull(userSession.getUpdateTime());
        assertNotNull(userSession.getSessionTimeout());
    }

    @Test
    public void testDeleteUserSession_Token() {
        // ????????????
        String token = randomString();

        // mock redis ??????
        loginUserRedisDAO.set(token, new LoginUser());
        // mock db ??????
        UserSessionDO userSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
            o.setToken(token);
        });
        userSessionMapper.insert(userSession);

        // ??????
        userSessionService.deleteUserSession(token);
        // ????????????????????????
        assertNull(loginUserRedisDAO.get(token));
        assertNull(userSessionMapper.selectOne(UserSessionDO::getToken, token));
    }

    @Test
    public void testDeleteUserSession_Id() {
        // mock db ??????
        UserSessionDO userSession = randomPojo(UserSessionDO.class, o -> {
            o.setUserType(randomEle(UserTypeEnum.values()).getValue());
        });
        userSessionMapper.insert(userSession);
        // mock redis ??????
        loginUserRedisDAO.set(userSession.getToken(), new LoginUser());

        // ????????????
        Long id = userSession.getId();

        // ??????
        userSessionService.deleteUserSession(id);
        // ????????????????????????
        assertNull(loginUserRedisDAO.get(userSession.getToken()));
        assertNull(userSessionMapper.selectById(id));
    }

}
