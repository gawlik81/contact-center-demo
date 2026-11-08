package com.contactcenter.domain.user;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.Mockito.mock;

/**
 * Lets tests from other packages register the real (package-private) {@code UserServiceImpl} in a
 * {@link com.contactcenter.support.JpaTestContext} so the {@code @Transactional} proxy wraps it.
 */
public final class UserServiceTestSupport {

    private UserServiceTestSupport() {
    }

    public static void registerUserService(AnnotationConfigApplicationContext ctx, RabbitTemplate rabbit,
                                           RedisTemplate<String, Object> redis, ContactService contactService,
                                           ApplicationEventPublisher eventPublisher) {
        jakarta.persistence.EntityManagerFactory emf =
                ctx.getBeanFactory().getBean(jakarta.persistence.EntityManagerFactory.class);
        AppUserRepository users = new org.springframework.data.jpa.repository.support
                .JpaRepositoryFactory(org.springframework.orm.jpa.SharedEntityManagerCreator
                .createSharedEntityManager(emf)).getRepository(AppUserRepository.class);
        ctx.registerBean("userService", UserServiceImpl.class, () -> new UserServiceImpl(
                users, mock(PasswordEncoder.class), mock(TenantResourceLimitService.class),
                rabbit, redis, contactService, eventPublisher));
    }
}
