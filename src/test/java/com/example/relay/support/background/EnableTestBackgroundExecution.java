package com.example.relay.support.background;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.annotation.DirtiesContext;

@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public @interface EnableTestBackgroundExecution {

    TestBackgroundComponent[] value();
}
