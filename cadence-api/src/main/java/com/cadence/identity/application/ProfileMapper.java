package com.cadence.identity.application;

import com.cadence.identity.domain.User;
import org.mapstruct.Mapper;

@Mapper
interface ProfileMapper {

    UserProfile toProfile(User user);
}
