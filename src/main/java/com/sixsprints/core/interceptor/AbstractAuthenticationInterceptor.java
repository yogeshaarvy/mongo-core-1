package com.sixsprints.core.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import com.sixsprints.core.annotation.Authenticated;
import com.sixsprints.core.domain.AbstractMongoEntity;
import com.sixsprints.core.enums.AccessPermission;
import com.sixsprints.core.enums.Restriction;
import com.sixsprints.core.exception.BaseException;
import com.sixsprints.core.exception.EntityNotFoundException;
import com.sixsprints.core.exception.NotAuthenticatedException;
import com.sixsprints.core.service.GenericCrudService;
import com.sixsprints.core.utils.ApplicationContext;
import com.sixsprints.core.utils.AuthUtil;

public abstract class AbstractAuthenticationInterceptor<T extends AbstractMongoEntity>
  extends HandlerInterceptorAdapter {

  private GenericCrudService<T> userService;

  public AbstractAuthenticationInterceptor(GenericCrudService<T> userService) {
    this.userService = userService;
  }

  @Override
  public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
    Object handler) throws Exception {
    if (!(handler instanceof HandlerMethod)) {
      return true;
    }
    Method method = ((HandlerMethod) handler).getMethod();
    if (!(method.getDeclaringClass().isAnnotationPresent(Authenticated.class)
      || method.isAnnotationPresent(Authenticated.class))) {
      return true;
    }
    Authenticated annotation = mergeAnnotationData(method);
    String token = httpServletRequest.getHeader(auhtTokenKey());
    if (StringUtils.isEmpty(token)) {
      token = httpServletRequest.getParameter(auhtTokenKey());
    }
    T user = checkUser(annotation, token);
    checkRestriction(user, annotation.restriction());
    postProcessor(user);
    return true;
  }

  protected void checkRestriction(T user, Restriction restriction) throws BaseException {
  }

  protected abstract String auhtTokenKey();

  protected abstract void checkUserPermissions(T user, Authenticated authAnnotation)
    throws NotAuthenticatedException, EntityNotFoundException;

  protected abstract void checkIfTokenInvalid(T user, String token, Authenticated authAnnotation)
    throws NotAuthenticatedException;

  protected void postProcessor(T user) {
    ApplicationContext.setCurrentUser(user);
  }

  protected void throwException(Authenticated authAnnotation, String message) throws NotAuthenticatedException {
    if (authAnnotation.required()) {
      throw NotAuthenticatedException.childBuilder().error(message).build();
    }
  }

  protected String unauthorisedErrorMessage(T user) {
    return "You are not authorized to take this action.";
  }

  protected String inactiveErrorMessage(T user) {
    return "User account is not active.";
  }

  protected String tokenInvalidErrorMessage() {
    return "Token is invalid!";
  }

  protected String tokenEmptyErrorMessage() {
    return "Token is empty!";
  }

  private T checkUser(Authenticated authAnnotation, String token)
    throws NotAuthenticatedException, EntityNotFoundException {
    Boolean tokenEmpty = checkIfTokenEmpty(authAnnotation, token);
    if (tokenEmpty) {
      return null;
    }
    T user = decodeUser(token, authAnnotation);
    if (user == null) {
      return null;
    }
    checkIfTokenInvalid(user, token, authAnnotation);
    checkIfActive(user, authAnnotation);
    checkUserPermissions(user, authAnnotation);
    return user;
  }

  private void checkIfActive(T user, Authenticated authAnnotation) throws NotAuthenticatedException {
    if (!user.getActive()) {
      throwException(authAnnotation, inactiveErrorMessage(user));
    }
  }

  private T decodeUser(String token, Authenticated authAnnotation) throws NotAuthenticatedException {
    T user = null;
    try {
      String userId = AuthUtil.decodeToken(token);
      user = userService.findOne(userId);
    } catch (BaseException ex) {
      throwException(authAnnotation, ex.getMessage());
    }
    return user;
  }

  private Boolean checkIfTokenEmpty(Authenticated authAnnotation, String token) throws NotAuthenticatedException {
    if (StringUtils.isEmpty(token)) {
      throwException(authAnnotation, tokenEmptyErrorMessage());
      return true;
    }
    return false;
  }

  private Authenticated mergeAnnotationData(Method method) {
    Authenticated annotationClass = method.getDeclaringClass().getAnnotation(Authenticated.class);
    Authenticated annotationMethod = method.getAnnotation(Authenticated.class);

    if (annotationMethod == null) {
      return annotationClass;
    }

    if (annotationClass == null) {
      return annotationMethod;
    }

    return new Authenticated() {

      @Override
      public Class<? extends Annotation> annotationType() {
        return Authenticated.class;
      }

      @Override
      public boolean required() {
        return annotationMethod.required();
      }

      @Override
      public String entity() {
        String result = StringUtils.isEmpty(annotationMethod.entity()) ? annotationClass.entity()
          : annotationMethod.entity();
        if (StringUtils.isEmpty(result)) {
          result = "ANY";
        }
        return result;
      }

      @Override
      public Restriction restriction() {
        Restriction result = Restriction.NULL == annotationMethod.restriction() ? annotationClass.restriction()
          : annotationMethod.restriction();
        if (result == Restriction.NULL) {
          result = Restriction.NONE;
        }
        return result;
      }

      @Override
      public AccessPermission access() {
        AccessPermission result = AccessPermission.NULL == annotationMethod.access() ? annotationClass.access()
          : annotationMethod.access();
        if (result == AccessPermission.NULL) {
          result = AccessPermission.ANY;
        }
        return result;
      }
    };
  }

}