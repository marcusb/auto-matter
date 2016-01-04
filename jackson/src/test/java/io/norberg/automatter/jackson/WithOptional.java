package io.norberg.automatter.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.base.Optional;

import io.norberg.automatter.AutoMatter;

@AutoMatter
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public interface WithOptional {
  int a();
  Optional<String> b();
}
