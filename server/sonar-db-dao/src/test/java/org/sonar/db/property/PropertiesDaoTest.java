/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.db.property;

import com.google.common.collect.ImmutableMap;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.sonar.api.impl.utils.AlwaysIncreasingSystem2;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.EmailSubscriberDto;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.user.UserDto;

import static com.google.common.collect.ImmutableSet.of;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.sonar.db.property.PropertyTesting.newComponentPropertyDto;
import static org.sonar.db.property.PropertyTesting.newGlobalPropertyDto;
import static org.sonar.db.property.PropertyTesting.newPropertyDto;
import static org.sonar.db.property.PropertyTesting.newUserPropertyDto;

@RunWith(DataProviderRunner.class)
public class PropertiesDaoTest {
  private static final String VALUE_SIZE_4000 = String.format("%1$4000.4000s", "*");
  private static final String VALUE_SIZE_4001 = VALUE_SIZE_4000 + "P";
  private static final long INITIAL_DATE = 1_444_000L;

  private AlwaysIncreasingSystem2 system2 = new AlwaysIncreasingSystem2(INITIAL_DATE, 1);

  @Rule
  public ExpectedException thrown = ExpectedException.none();
  @Rule
  public DbTester db = DbTester.create(system2);

  private DbClient dbClient = db.getDbClient();
  private DbSession session = db.getSession();

  private PropertiesDao underTest = db.getDbClient().propertiesDao();

  @Test
  public void shouldFindUsersForNotification() {
    ComponentDto project1 = insertPrivateProject("uuid_45");
    ComponentDto project2 = insertPrivateProject("uuid_56");
    UserDto user1 = db.users().insertUser(u -> u.setLogin("user1"));
    UserDto user2 = db.users().insertUser(u -> u.setLogin("user2"));
    UserDto user3 = db.users().insertUser(u -> u.setLogin("user3"));
    insertProperty("notification.NewViolations.Email", "true", project1.uuid(), user2.getId());
    insertProperty("notification.NewViolations.Twitter", "true", null, user3.getId());
    insertProperty("notification.NewViolations.Twitter", "true", project2.uuid(), user1.getId());
    insertProperty("notification.NewViolations.Twitter", "true", project1.uuid(), user2.getId());
    insertProperty("notification.NewViolations.Twitter", "true", project2.uuid(), user3.getId());
    db.users().insertProjectPermissionOnUser(user2, UserRole.USER, project1);
    db.users().insertProjectPermissionOnUser(user3, UserRole.USER, project2);
    db.users().insertProjectPermissionOnUser(user1, UserRole.USER, project2);

    assertThat(underTest.findUsersForNotification("NewViolations", "Email", null))
      .isEmpty();

    assertThat(underTest.findUsersForNotification("NewViolations", "Email", "uuid_78"))
      .isEmpty();

    assertThat(underTest.findUsersForNotification("NewViolations", "Email", project1.getKey()))
      .containsOnly(new Subscriber("user2", false));

    assertThat(underTest.findUsersForNotification("NewViolations", "Email", project2.getKey()))
      .isEmpty();

    assertThat(underTest.findUsersForNotification("NewViolations", "Twitter", null))
      .containsOnly(new Subscriber("user3", true));

    assertThat(underTest.findUsersForNotification("NewViolations", "Twitter", "uuid_78"))
      .containsOnly(new Subscriber("user3", true));

    assertThat(underTest.findUsersForNotification("NewViolations", "Twitter", project1.getKey()))
      .containsOnly(new Subscriber("user2", false), new Subscriber("user3", true));

    assertThat(underTest.findUsersForNotification("NewViolations", "Twitter", project2.getKey()))
      .containsOnly(new Subscriber("user1", false), new Subscriber("user3", true), new Subscriber("user3", false));
  }

  @Test
  public void hasNotificationSubscribers() {
    int userId1 = db.users().insertUser(u -> u.setLogin("user1")).getId();
    int userId2 = db.users().insertUser(u -> u.setLogin("user2")).getId();
    String projectUuid = randomAlphabetic(8);
    db.components().insertPrivateProject(db.getDefaultOrganization(), projectUuid);

    // global subscription
    insertProperty("notification.DispatcherWithGlobalSubscribers.Email", "true", null, userId2);
    // project subscription
    insertProperty("notification.DispatcherWithProjectSubscribers.Email", "true", projectUuid, userId1);
    insertProperty("notification.DispatcherWithGlobalAndProjectSubscribers.Email", "true", "uuid56", userId1);
    insertProperty("notification.DispatcherWithGlobalAndProjectSubscribers.Email", "true", projectUuid, userId1);
    // global subscription
    insertProperty("notification.DispatcherWithGlobalAndProjectSubscribers.Email", "true", null, userId2);

    // Nobody is subscribed
    assertThat(underTest.hasProjectNotificationSubscribersForDispatchers(projectUuid, singletonList("NotSexyDispatcher")))
      .isFalse();

    // Global subscribers
    assertThat(underTest.hasProjectNotificationSubscribersForDispatchers(projectUuid, singletonList("DispatcherWithGlobalSubscribers")))
      .isTrue();

    // Project subscribers
    assertThat(underTest.hasProjectNotificationSubscribersForDispatchers(projectUuid, singletonList("DispatcherWithProjectSubscribers")))
      .isTrue();
    assertThat(underTest.hasProjectNotificationSubscribersForDispatchers("PROJECT_B", singletonList("DispatcherWithProjectSubscribers")))
      .isFalse();

    // Global + Project subscribers
    assertThat(underTest.hasProjectNotificationSubscribersForDispatchers(projectUuid, singletonList("DispatcherWithGlobalAndProjectSubscribers")))
      .isTrue();
    assertThat(underTest.hasProjectNotificationSubscribersForDispatchers("PROJECT_B", singletonList("DispatcherWithGlobalAndProjectSubscribers")))
      .isTrue();
  }

  @Test
  public void findEmailRecipientsForNotification_returns_empty_on_empty_properties_table() {
    db.users().insertUser();
    String dispatcherKey = randomAlphabetic(5);
    String channelKey = randomAlphabetic(6);
    String projectKey = randomAlphabetic(7);

    Set<EmailSubscriberDto> subscribers = underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey);

    assertThat(subscribers).isEmpty();
  }

  @Test
  public void findEmailRecipientsForNotification_with_logins_returns_empty_on_empty_properties_table() {
    db.users().insertUser();
    String dispatcherKey = randomAlphabetic(5);
    String channelKey = randomAlphabetic(6);
    String projectKey = randomAlphabetic(7);
    Set<String> logins = of("user1", "user2");

    Set<EmailSubscriberDto> subscribers = underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, logins);

    assertThat(subscribers).isEmpty();
  }

  @Test
  public void findEmailRecipientsForNotification_finds_only_globally_subscribed_users_if_projectKey_is_null() {
    int userId1 = db.users().insertUser(withEmail("user1")).getId();
    int userId2 = db.users().insertUser(withEmail("user2")).getId();
    int userId3 = db.users().insertUser(withEmail("user3")).getId();
    int userId4 = db.users().insertUser(withEmail("user4")).getId();
    String projectUuid = insertPrivateProject("PROJECT_A").uuid();
    String dispatcherKey = randomAlphabetic(5);
    String otherDispatcherKey = randomAlphabetic(6);
    String channelKey = randomAlphabetic(7);
    String otherChannelKey = randomAlphabetic(8);
    // user1 subscribed only globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId1);
    // user2 subscribed on project and globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId2);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId2);
    // user3 subscribed on project only
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId3);
    // user4 did not subscribe
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "false", projectUuid, userId4);

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null))
      .containsOnly(EmailSubscriberDto.create("user1", true, emailOf("user1")), EmailSubscriberDto.create("user2", true, emailOf("user2")));

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, otherChannelKey, null))
      .isEmpty();
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), otherDispatcherKey, channelKey, null))
      .isEmpty();
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), channelKey, dispatcherKey, null))
      .isEmpty();
  }

  @Test
  public void findEmailRecipientsForNotification_with_logins_finds_only_globally_subscribed_specified_users_if_projectKey_is_null() {
    int userId1 = db.users().insertUser(withEmail("user1")).getId();
    int userId2 = db.users().insertUser(withEmail("user2")).getId();
    int userId3 = db.users().insertUser(withEmail("user3")).getId();
    int userId4 = db.users().insertUser(withEmail("user4")).getId();
    String projectUuid = insertPrivateProject("PROJECT_A").uuid();
    String dispatcherKey = randomAlphabetic(5);
    String otherDispatcherKey = randomAlphabetic(6);
    String channelKey = randomAlphabetic(7);
    String otherChannelKey = randomAlphabetic(8);
    // user1 subscribed only globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId1);
    // user2 subscribed on project and globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId2);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId2);
    // user3 subscribed on project only
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId3);
    // user4 did not subscribe
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "false", projectUuid, userId4);
    Set<String> allLogins = of("user1", "user2", "user3", "user4");

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null, allLogins))
      .containsOnly(EmailSubscriberDto.create("user1", true, emailOf("user1")), EmailSubscriberDto.create("user2", true, emailOf("user2")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null, of("user1", "user2")))
      .containsOnly(EmailSubscriberDto.create("user1", true, emailOf("user1")), EmailSubscriberDto.create("user2", true, emailOf("user2")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null, of("user2")))
      .containsOnly(EmailSubscriberDto.create("user2", true, emailOf("user2")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null, of("user1")))
      .containsOnly(EmailSubscriberDto.create("user1", true, emailOf("user1")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null, of()))
      .isEmpty();

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, otherChannelKey, null, allLogins))
      .isEmpty();
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), otherDispatcherKey, channelKey, null, allLogins))
      .isEmpty();
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), channelKey, dispatcherKey, null, allLogins))
      .isEmpty();
  }

  @Test
  public void findEmailRecipientsForNotification_finds_global_and_project_subscribed_users_when_projectKey_is_non_null() {
    int userId1 = db.users().insertUser(withEmail("user1")).getId();
    int userId2 = db.users().insertUser(withEmail("user2")).getId();
    int userId3 = db.users().insertUser(withEmail("user3")).getId();
    int userId4 = db.users().insertUser(withEmail("user4")).getId();
    String projectKey = randomAlphabetic(3);
    String otherProjectKey = randomAlphabetic(4);
    String projectUuid = insertPrivateProject(projectKey).uuid();
    String dispatcherKey = randomAlphabetic(5);
    String otherDispatcherKey = randomAlphabetic(6);
    String channelKey = randomAlphabetic(7);
    String otherChannelKey = randomAlphabetic(8);
    // user1 subscribed only globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId1);
    // user2 subscribed on project and globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId2);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId2);
    // user3 subscribed on project only
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId3);
    // user4 did not subscribe
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "false", projectUuid, userId4);

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")),
        EmailSubscriberDto.create("user2", true, emailOf("user2")), EmailSubscriberDto.create("user2", false, "user2@foo"),
        EmailSubscriberDto.create("user3", false, emailOf("user3")));

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, otherProjectKey))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")),
        EmailSubscriberDto.create("user2", true, emailOf("user2")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, otherChannelKey, otherProjectKey))
      .isEmpty();
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), otherDispatcherKey, channelKey, otherProjectKey))
      .isEmpty();
  }

  @Test
  public void findEmailRecipientsForNotification_with_logins_finds_global_and_project_subscribed_specified_users_when_projectKey_is_non_null() {
    int userId1 = db.users().insertUser(withEmail("user1")).getId();
    int userId2 = db.users().insertUser(withEmail("user2")).getId();
    int userId3 = db.users().insertUser(withEmail("user3")).getId();
    int userId4 = db.users().insertUser(withEmail("user4")).getId();
    String projectKey = randomAlphabetic(3);
    String otherProjectKey = randomAlphabetic(4);
    String projectUuid = insertPrivateProject(projectKey).uuid();
    String dispatcherKey = randomAlphabetic(5);
    String otherDispatcherKey = randomAlphabetic(6);
    String channelKey = randomAlphabetic(7);
    String otherChannelKey = randomAlphabetic(8);
    // user1 subscribed only globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId1);
    // user2 subscribed on project and globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId2);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId2);
    // user3 subscribed on project only
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId3);
    // user4 did not subscribe
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "false", projectUuid, userId4);
    Set<String> allLogins = of("user1", "user2", "user3", "user4");

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, allLogins))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")),
        EmailSubscriberDto.create("user2", true, emailOf("user2")), EmailSubscriberDto.create("user2", false, "user2@foo"),
        EmailSubscriberDto.create("user3", false, emailOf("user3")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, of("user1")))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, of("user2")))
      .containsOnly(
        EmailSubscriberDto.create("user2", true, emailOf("user2")), EmailSubscriberDto.create("user2", false, "user2@foo"));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, of("user3")))
      .containsOnly(EmailSubscriberDto.create("user3", false, emailOf("user3")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, of()))
      .isEmpty();

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, otherProjectKey, allLogins))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")),
        EmailSubscriberDto.create("user2", true, emailOf("user2")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, otherChannelKey, otherProjectKey, allLogins))
      .isEmpty();
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), otherDispatcherKey, channelKey, otherProjectKey, allLogins))
      .isEmpty();
  }

  @Test
  public void findEmailRecipientsForNotification_ignores_subscribed_users_without_email() {
    int userId1 = db.users().insertUser(withEmail("user1")).getId();
    int userId2 = db.users().insertUser(noEmail("user2")).getId();
    int userId3 = db.users().insertUser(withEmail("user3")).getId();
    int userId4 = db.users().insertUser(noEmail("user4")).getId();
    String projectKey = randomAlphabetic(3);
    String projectUuid = insertPrivateProject(projectKey).uuid();
    String dispatcherKey = randomAlphabetic(4);
    String channelKey = randomAlphabetic(5);
    // user1 and user2 subscribed on project and globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId1);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId1);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId2);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId2);
    // user3 and user4 subscribed only globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId3);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId4);

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")), EmailSubscriberDto.create("user1", false, emailOf("user1")),
        EmailSubscriberDto.create("user3", true, emailOf("user3")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")),
        EmailSubscriberDto.create("user3", true, emailOf("user3")));
  }

  @Test
  public void findEmailRecipientsForNotification_with_logins_ignores_subscribed_users_without_email() {
    int userId1 = db.users().insertUser(withEmail("user1")).getId();
    int userId2 = db.users().insertUser(noEmail("user2")).getId();
    int userId3 = db.users().insertUser(withEmail("user3")).getId();
    int userId4 = db.users().insertUser(noEmail("user4")).getId();
    Set<String> allLogins = of("user1", "user2", "user3");
    String projectKey = randomAlphabetic(3);
    String projectUuid = insertPrivateProject(projectKey).uuid();
    String dispatcherKey = randomAlphabetic(4);
    String channelKey = randomAlphabetic(5);
    // user1 and user2 subscribed on project and globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId1);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId1);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId2);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", projectUuid, userId2);
    // user3 and user4 subscribed only globally
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId3);
    insertProperty(propertyKeyOf(dispatcherKey, channelKey), "true", null, userId4);

    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, projectKey, allLogins))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")), EmailSubscriberDto.create("user1", false, emailOf("user1")),
        EmailSubscriberDto.create("user3", true, emailOf("user3")));
    assertThat(underTest.findEmailSubscribersForNotification(db.getSession(), dispatcherKey, channelKey, null, allLogins))
      .containsOnly(
        EmailSubscriberDto.create("user1", true, emailOf("user1")),
        EmailSubscriberDto.create("user3", true, emailOf("user3")));
  }

  @Test
  public void selectGlobalProperties() {
    // global
    long id1 = insertProperty("global.one", "one", null, null);
    long id2 = insertProperty("global.two", "two", null, null);

    List<PropertyDto> properties = underTest.selectGlobalProperties();
    assertThat(properties.size())
      .isEqualTo(2);

    assertThat(findByKey(properties, "global.one"))
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getUserId, PropertyDto::getValue)
      .containsExactly("global.one", null, null, "one");

    assertThat(findByKey(properties, "global.two"))
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getUserId, PropertyDto::getValue)
      .containsExactly("global.two", null, null, "two");
  }

  @Test
  @UseDataProvider("allValuesForSelect")
  public void selectGlobalProperties_supports_all_values(String dbValue, String expected) {
    insertProperty("global.one", dbValue, null, null);

    List<PropertyDto> dtos = underTest.selectGlobalProperties();
    assertThat(dtos)
      .hasSize(1);

    assertThat(dtos.iterator().next())
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getUserId, PropertyDto::getValue)
      .containsExactly("global.one", null, null, expected);
  }

  @Test
  public void selectGlobalProperty() {
    // global
    insertProperty("global.one", "one", null, null);
    insertProperty("global.two", "two", null, null);
    // project
    insertProperty("project.one", "one", "uuid10", null);
    // user
    insertProperty("user.one", "one", null, 100);

    assertThat(underTest.selectGlobalProperty("global.one"))
      .extracting(PropertyDto::getComponentUuid, PropertyDto::getUserId, PropertyDto::getValue)
      .containsExactly(null, null, "one");

    assertThat(underTest.selectGlobalProperty("project.one")).isNull();
    assertThat(underTest.selectGlobalProperty("user.one")).isNull();
    assertThat(underTest.selectGlobalProperty("unexisting")).isNull();
  }

  @Test
  @UseDataProvider("allValuesForSelect")
  public void selectGlobalProperty_supports_all_values(String dbValue, String expected) {
    insertProperty("global.one", dbValue, null, null);

    assertThat(underTest.selectGlobalProperty("global.one"))
      .extracting(PropertyDto::getComponentUuid, PropertyDto::getUserId, PropertyDto::getValue)
      .containsExactly(null, null, expected);
  }

  @Test
  public void selectProjectProperties() {
    ComponentDto projectDto = insertPrivateProject("A");
    String projectUuid = projectDto.uuid();
    // global
    insertProperty("global.one", "one", null, null);
    insertProperty("global.two", "two", null, null);
    // project
    insertProperty("project.one", "Pone", projectUuid, null);
    insertProperty("project.two", "Ptwo", projectUuid, null);

    List<PropertyDto> dtos = underTest.selectProjectProperties(projectDto.getDbKey());
    assertThat(dtos)
      .hasSize(2);
    assertThat(findByKey(dtos, "project.one"))
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getValue)
      .containsExactly("project.one", projectUuid, "Pone");

    assertThat(findByKey(dtos, "project.two"))
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getValue)
      .containsExactly("project.two", projectUuid, "Ptwo");
  }

  @Test
  @UseDataProvider("allValuesForSelect")
  public void selectProjectProperties_supports_all_values(String dbValue, String expected) {
    ComponentDto projectDto = insertPrivateProject("A");
    insertProperty("project.one", dbValue, projectDto.uuid(), null);

    List<PropertyDto> dtos = underTest.selectProjectProperties(projectDto.getDbKey());
    assertThat(dtos).hasSize(1);

    assertThat(dtos.iterator().next())
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getValue)
      .containsExactly("project.one", projectDto.uuid(), expected);
  }

  @DataProvider
  public static Object[][] allValuesForSelect() {
    return new Object[][]{
      {null, ""},
      {"", ""},
      {"some value", "some value"},
      {VALUE_SIZE_4000, VALUE_SIZE_4000},
      {VALUE_SIZE_4001, VALUE_SIZE_4001}
    };
  }

  @Test
  public void selectProjectProperty() {
    insertProperty("project.one", "one", "uuid10", null);

    PropertyDto property = underTest.selectProjectProperty("uuid10", "project.one");

    assertThat(property)
      .extracting(PropertyDto::getKey, PropertyDto::getComponentUuid, PropertyDto::getUserId, PropertyDto::getValue)
      .containsExactly("project.one", "uuid10", null, "one");
  }

  @Test
  public void select_by_query() {
    // global
    insertProperty("global.one", "one", null, null);
    insertProperty("global.two", "two", null, null);
    // struts
    insertProperty("struts.one", "one", "uuid10", null);
    // commons
    insertProperty("commonslang.one", "one", "uuid11", null);
    // user
    insertProperty("user.one", "one", null, 100);
    insertProperty("user.two", "two", "uuid10", 100);
    // other
    insertProperty("other.one", "one", "uuid12", null);

    List<PropertyDto> results = underTest.selectByQuery(PropertyQuery.builder().setKey("user.two").setComponentUuid("uuid10")
      .setUserId(100).build(), db.getSession());
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getValue()).isEqualTo("two");

    results = underTest.selectByQuery(PropertyQuery.builder().setKey("user.one").setUserId(100).build(), db.getSession());
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getValue()).isEqualTo("one");
  }

  @Test
  public void select_global_properties_by_keys() {
    insertPrivateProject("A");
    int userId = db.users().insertUser(u -> u.setLogin("B")).getId();

    String key = "key";
    String anotherKey = "anotherKey";
    insertProperty(key, "value", null, null);
    insertProperty(key, "value", "uuid10", null);
    insertProperty(key, "value", null, userId);
    insertProperty(anotherKey, "value", null, null);

    insertProperty("key1", "value", null, null);
    insertProperty("key2", "value", null, null);
    insertProperty("key3", "value", null, null);

    assertThat(underTest.selectGlobalPropertiesByKeys(session, newHashSet(key)))
      .extracting("key")
      .containsExactly(key);
    assertThat(underTest.selectGlobalPropertiesByKeys(session, newHashSet(key, anotherKey)))
      .extracting("key")
      .containsExactly(key, anotherKey);
    assertThat(underTest.selectGlobalPropertiesByKeys(session, newHashSet(key, anotherKey, "unknown")))
      .extracting("key")
      .containsExactly(key, anotherKey);

    assertThat(underTest.selectGlobalPropertiesByKeys(session, newHashSet("key2", "key1", "key3")))
      .extracting("key")
      .containsExactly("key1", "key2", "key3");

    assertThat(underTest.selectGlobalPropertiesByKeys(session, newHashSet("unknown")))
      .isEmpty();
  }

  @Test
  public void select_component_properties_by_ids() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto project2 = db.components().insertPrivateProject();
    UserDto user = db.users().insertUser();

    String key = "key";
    String anotherKey = "anotherKey";
    insertProperties(
      newGlobalPropertyDto().setKey(key),
      newComponentPropertyDto(project).setKey(key),
      newComponentPropertyDto(project2).setKey(key),
      newComponentPropertyDto(project2).setKey(anotherKey),
      newUserPropertyDto(user).setKey(key));

    assertThat(underTest.selectPropertiesByComponentUuids(session, newHashSet(project.uuid())))
      .extracting("key", "componentUuid").containsOnly(tuple(key, project.uuid()));
    assertThat(underTest.selectPropertiesByComponentUuids(session, newHashSet(project.uuid(), project2.uuid())))
      .extracting("key", "componentUuid").containsOnly(
      tuple(key, project.uuid()),
      tuple(key, project2.uuid()),
      tuple(anotherKey, project2.uuid()));

    assertThat(underTest.selectPropertiesByComponentUuids(session, newHashSet("uuid123456789"))).isEmpty();
  }

  @Test
  public void select_properties_by_keys_and_component_ids() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto project2 = db.components().insertPrivateProject();
    UserDto user = db.users().insertUser();

    String key = "key";
    String anotherKey = "anotherKey";
    insertProperties(
      newGlobalPropertyDto().setKey(key),
      newComponentPropertyDto(project).setKey(key),
      newComponentPropertyDto(project2).setKey(key),
      newComponentPropertyDto(project2).setKey(anotherKey),
      newUserPropertyDto(user).setKey(key));

    assertThat(underTest.selectPropertiesByKeysAndComponentUuids(session, newHashSet(key), newHashSet(project.uuid())))
      .extracting("key", "componentUuid").containsOnly(tuple(key, project.uuid()));
    assertThat(underTest.selectPropertiesByKeysAndComponentUuids(session, newHashSet(key), newHashSet(project.uuid(), project2.uuid())))
      .extracting("key", "componentUuid").containsOnly(
      tuple(key, project.uuid()),
      tuple(key, project2.uuid()));
    assertThat(underTest.selectPropertiesByKeysAndComponentUuids(session, newHashSet(key, anotherKey), newHashSet(project.uuid(), project2.uuid())))
      .extracting("key", "componentUuid").containsOnly(
      tuple(key, project.uuid()),
      tuple(key, project2.uuid()),
      tuple(anotherKey, project2.uuid()));

    assertThat(underTest.selectPropertiesByKeysAndComponentUuids(session, newHashSet("unknown"), newHashSet(project.uuid()))).isEmpty();
    assertThat(underTest.selectPropertiesByKeysAndComponentUuids(session, newHashSet("key"), newHashSet("uuid123456789"))).isEmpty();
    assertThat(underTest.selectPropertiesByKeysAndComponentUuids(session, newHashSet("unknown"), newHashSet("uuid123456789"))).isEmpty();
  }

  @Test
  public void select_by_key_and_matching_value() {
    ComponentDto project1 = db.components().insertPrivateProject();
    ComponentDto project2 = db.components().insertPrivateProject();
    db.properties().insertProperties(
      newComponentPropertyDto("key", "value", project1),
      newComponentPropertyDto("key", "value", project2),
      newGlobalPropertyDto("key", "value"),
      newComponentPropertyDto("another key", "value", project1));

    assertThat(underTest.selectByKeyAndMatchingValue(db.getSession(), "key", "value"))
      .extracting(PropertyDto::getValue, PropertyDto::getComponentUuid)
      .containsExactlyInAnyOrder(
        tuple("value", project1.uuid()),
        tuple("value", project2.uuid()),
        tuple("value", null));
  }

  @Test
  public void selectByKeyAndUserIdAndComponentQualifier() {
    UserDto user1 = db.users().insertUser();
    UserDto user2 = db.users().insertUser();
    ComponentDto project1 = db.components().insertPrivateProject();
    ComponentDto file1 = db.components().insertComponent(ComponentTesting.newFileDto(project1));
    ComponentDto project2 = db.components().insertPrivateProject();
    db.properties().insertProperties(
      newPropertyDto("key", "1", project1, user1),
      newPropertyDto("key", "2", project2, user1),
      newPropertyDto("key", "3", file1, user1),
      newPropertyDto("another key", "4", project1, user1),
      newPropertyDto("key", "5", project1, user2),
      newGlobalPropertyDto("key", "global"));

    assertThat(underTest.selectByKeyAndUserIdAndComponentQualifier(db.getSession(), "key", user1.getId(), "TRK"))
      .extracting(PropertyDto::getValue).containsExactlyInAnyOrder("1", "2");
    assertThat(underTest.selectByKeyAndUserIdAndComponentQualifier(db.getSession(), "key", user1.getId(), "FIL"))
      .extracting(PropertyDto::getValue).containsExactlyInAnyOrder("3");
    assertThat(underTest.selectByKeyAndUserIdAndComponentQualifier(db.getSession(), "key", user2.getId(), "FIL")).isEmpty();
  }

  @Test
  public void saveProperty_inserts_global_properties_when_they_do_not_exist_in_db() {
    underTest.saveProperty(new PropertyDto().setKey("global.null").setValue(null));
    underTest.saveProperty(new PropertyDto().setKey("global.empty").setValue(""));
    underTest.saveProperty(new PropertyDto().setKey("global.text").setValue("some text"));
    underTest.saveProperty(new PropertyDto().setKey("global.4000").setValue(VALUE_SIZE_4000));
    underTest.saveProperty(new PropertyDto().setKey("global.clob").setValue(VALUE_SIZE_4001));

    assertThatPropertiesRow("global.null")
      .hasNoComponentUuid()
      .hasNoUserId()
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 2);
    assertThatPropertiesRow("global.empty")
      .hasNoComponentUuid()
      .hasNoUserId()
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 3);
    assertThatPropertiesRow("global.text")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("some text")
      .hasCreatedAt(INITIAL_DATE + 4);
    assertThatPropertiesRow("global.4000")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue(VALUE_SIZE_4000)
      .hasCreatedAt(INITIAL_DATE + 5);
    assertThatPropertiesRow("global.clob")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasClobValue(VALUE_SIZE_4001)
      .hasCreatedAt(INITIAL_DATE + 6);
  }

  @Test
  public void saveProperty_inserts_component_properties_when_they_do_not_exist_in_db() {
    String componentUuid = "uuid12";
    underTest.saveProperty(new PropertyDto().setKey("component.null").setComponentUuid(componentUuid).setValue(null));
    underTest.saveProperty(new PropertyDto().setKey("component.empty").setComponentUuid(componentUuid).setValue(""));
    underTest.saveProperty(new PropertyDto().setKey("component.text").setComponentUuid(componentUuid).setValue("some text"));
    underTest.saveProperty(new PropertyDto().setKey("component.4000").setComponentUuid(componentUuid).setValue(VALUE_SIZE_4000));
    underTest.saveProperty(new PropertyDto().setKey("component.clob").setComponentUuid(componentUuid).setValue(VALUE_SIZE_4001));

    assertThatPropertiesRow("component.null")
      .hasComponentUuid(componentUuid)
      .hasNoUserId()
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 2);
    assertThatPropertiesRow("component.empty")
      .hasComponentUuid(componentUuid)
      .hasNoUserId()
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 3);
    assertThatPropertiesRow("component.text")
      .hasComponentUuid(componentUuid)
      .hasNoUserId()
      .hasTextValue("some text")
      .hasCreatedAt(INITIAL_DATE + 4);
    assertThatPropertiesRow("component.4000")
      .hasComponentUuid(componentUuid)
      .hasNoUserId()
      .hasTextValue(VALUE_SIZE_4000)
      .hasCreatedAt(INITIAL_DATE + 5);
    assertThatPropertiesRow("component.clob")
      .hasComponentUuid(componentUuid)
      .hasNoUserId()
      .hasClobValue(VALUE_SIZE_4001)
      .hasCreatedAt(INITIAL_DATE + 6);
  }

  @Test
  public void saveProperty_inserts_user_properties_when_they_do_not_exist_in_db() {
    int userId = 100;
    underTest.saveProperty(new PropertyDto().setKey("user.null").setUserId(userId).setValue(null));
    underTest.saveProperty(new PropertyDto().setKey("user.empty").setUserId(userId).setValue(""));
    underTest.saveProperty(new PropertyDto().setKey("user.text").setUserId(userId).setValue("some text"));
    underTest.saveProperty(new PropertyDto().setKey("user.4000").setUserId(userId).setValue(VALUE_SIZE_4000));
    underTest.saveProperty(new PropertyDto().setKey("user.clob").setUserId(userId).setValue(VALUE_SIZE_4001));

    assertThatPropertiesRow("user.null")
      .hasNoComponentUuid()
      .hasUserId(userId)
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 2);
    assertThatPropertiesRow("user.empty")
      .hasNoComponentUuid()
      .hasUserId(userId)
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 3);
    assertThatPropertiesRow("user.text")
      .hasNoComponentUuid()
      .hasUserId(userId)
      .hasTextValue("some text")
      .hasCreatedAt(INITIAL_DATE + 4);
    assertThatPropertiesRow("user.4000")
      .hasNoComponentUuid()
      .hasUserId(userId)
      .hasTextValue(VALUE_SIZE_4000)
      .hasCreatedAt(INITIAL_DATE + 5);
    assertThatPropertiesRow("user.clob")
      .hasNoComponentUuid()
      .hasUserId(userId)
      .hasClobValue(VALUE_SIZE_4001)
      .hasCreatedAt(INITIAL_DATE + 6);
  }

  @Test
  @UseDataProvider("valueUpdatesDataProvider")
  public void saveProperty_deletes_then_inserts_global_properties_when_they_exist_in_db(@Nullable String oldValue, @Nullable String newValue) {
    long id = insertProperty("global", oldValue, null, null);

    underTest.saveProperty(new PropertyDto().setKey("global").setValue(newValue));

    assertThatPropertiesRow(id)
      .doesNotExist();

    PropertiesRowAssert propertiesRowAssert = assertThatPropertiesRow("global")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasCreatedAt(INITIAL_DATE + 3);
    if (newValue == null || newValue.isEmpty()) {
      propertiesRowAssert.isEmpty();
    } else if (newValue.length() > 4000) {
      propertiesRowAssert.hasClobValue(newValue);
    } else {
      propertiesRowAssert.hasTextValue(newValue);
    }
  }

  @Test
  @UseDataProvider("valueUpdatesDataProvider")
  public void saveProperty_deletes_then_inserts_component_properties_when_they_exist_in_db(@Nullable String oldValue, @Nullable String newValue) {
    String componentUuid = "uuid999";
    long id = insertProperty("global", oldValue, componentUuid, null);

    underTest.saveProperty(new PropertyDto().setKey("global").setComponentUuid(componentUuid).setValue(newValue));

    assertThatPropertiesRow(id)
      .doesNotExist();
    PropertiesRowAssert propertiesRowAssert = assertThatPropertiesRow("global")
      .hasComponentUuid(componentUuid)
      .hasNoUserId()
      .hasCreatedAt(INITIAL_DATE + 3);
    if (newValue == null || newValue.isEmpty()) {
      propertiesRowAssert.isEmpty();
    } else if (newValue.length() > 4000) {
      propertiesRowAssert.hasClobValue(newValue);
    } else {
      propertiesRowAssert.hasTextValue(newValue);
    }
  }

  @Test
  @UseDataProvider("valueUpdatesDataProvider")
  public void saveProperty_deletes_then_inserts_user_properties_when_they_exist_in_db(@Nullable String oldValue, @Nullable String newValue) {
    int userId = 90;
    long id = insertProperty("global", oldValue, null, userId);

    underTest.saveProperty(new PropertyDto().setKey("global").setUserId(userId).setValue(newValue));

    assertThatPropertiesRow(id)
      .doesNotExist();

    PropertiesRowAssert propertiesRowAssert = assertThatPropertiesRow("global")
      .hasNoComponentUuid()
      .hasUserId(userId)
      .hasCreatedAt(INITIAL_DATE + 3);
    if (newValue == null || newValue.isEmpty()) {
      propertiesRowAssert.isEmpty();
    } else if (newValue.length() > 4000) {
      propertiesRowAssert.hasClobValue(newValue);
    } else {
      propertiesRowAssert.hasTextValue(newValue);
    }
  }

  @DataProvider
  public static Object[][] valueUpdatesDataProvider() {
    return new Object[][]{
      {null, null},
      {null, ""},
      {null, "some value"},
      {null, VALUE_SIZE_4000},
      {null, VALUE_SIZE_4001},
      {"", null},
      {"", ""},
      {"", "some value"},
      {"", VALUE_SIZE_4000},
      {"", VALUE_SIZE_4001},
      {"a value", null},
      {"a value", ""},
      {"a value", "a value"},
      {"a value", "some value"},
      {"a value", VALUE_SIZE_4000},
      {"a value", VALUE_SIZE_4001},
      {VALUE_SIZE_4000, null},
      {VALUE_SIZE_4000, ""},
      {VALUE_SIZE_4000, "a value"},
      {VALUE_SIZE_4000, VALUE_SIZE_4000},
      {VALUE_SIZE_4000, VALUE_SIZE_4000.substring(1) + "a"},
      {VALUE_SIZE_4000, VALUE_SIZE_4001},
      {VALUE_SIZE_4001, null},
      {VALUE_SIZE_4001, ""},
      {VALUE_SIZE_4001, "a value"},
      {VALUE_SIZE_4001, VALUE_SIZE_4000},
      {VALUE_SIZE_4001, VALUE_SIZE_4001},
      {VALUE_SIZE_4001, VALUE_SIZE_4001 + "dfsdfs"},
    };
  }

  @Test
  public void delete_project_property() {
    insertPrivateProject("A");
    insertPrivateProject("B");
    insertPrivateProject("C");
    long id1 = insertProperty("global.one", "one", null, null);
    long id2 = insertProperty("global.two", "two", null, null);
    long id3 = insertProperty("struts.one", "one", "project1", null);
    long id4 = insertProperty("commonslang.one", "one", "project2", null);
    long id5 = insertProperty("user.one", "one", null, 100);
    long id6 = insertProperty("user.two", "two", null, 100);
    long id7 = insertProperty("other.one", "one", "project3", null);

    underTest.deleteProjectProperty("struts.one", "project1");

    assertThatPropertiesRow(id1)
      .hasKey("global.one")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("one");
    assertThatPropertiesRow(id2)
      .hasKey("global.two")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("two");
    assertThatPropertiesRow(id3)
      .doesNotExist();
    assertThatPropertiesRow(id4)
      .hasKey("commonslang.one")
      .hasComponentUuid("project2")
      .hasNoUserId()
      .hasTextValue("one");
    assertThatPropertiesRow(id5)
      .hasKey("user.one")
      .hasNoComponentUuid()
      .hasUserId(100)
      .hasTextValue("one");
    assertThatPropertiesRow(id6)
      .hasKey("user.two")
      .hasNoComponentUuid()
      .hasUserId(100)
      .hasTextValue("two");
    assertThatPropertiesRow(id7)
      .hasKey("other.one")
      .hasComponentUuid("project3")
      .hasNoUserId()
      .hasTextValue("one");
  }

  @Test
  public void delete_project_properties() {
    long id1 = insertProperty("sonar.profile.java", "Sonar Way", "uuid1", null);
    long id2 = insertProperty("sonar.profile.java", "Sonar Way", "uuid2", null);

    long id3 = insertProperty("sonar.profile.java", "Sonar Way", null, null);

    long id4 = insertProperty("sonar.profile.js", "Sonar Way", "uuid1", null);
    long id5 = insertProperty("sonar.profile.js", "Sonar Way", "uuid2", null);
    long id6 = insertProperty("sonar.profile.js", "Sonar Way", null, null);

    underTest.deleteProjectProperties("sonar.profile.java", "Sonar Way");

    assertThatPropertiesRow(id1)
      .doesNotExist();
    assertThatPropertiesRow(id2)
      .doesNotExist();
    assertThatPropertiesRow(id3)
      .hasKey("sonar.profile.java")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("Sonar Way");
    assertThatPropertiesRow(id4)
      .hasKey("sonar.profile.js")
      .hasComponentUuid("uuid1")
      .hasNoUserId()
      .hasTextValue("Sonar Way");
    assertThatPropertiesRow(id5)
      .hasKey("sonar.profile.js")
      .hasComponentUuid("uuid2")
      .hasNoUserId()
      .hasTextValue("Sonar Way");
    assertThatPropertiesRow(id6)
      .hasKey("sonar.profile.js")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("Sonar Way");
  }

  @Test
  public void deleteGlobalProperty() {
    // global
    long id1 = insertProperty("global.key", "new_global", null, null);
    long id2 = insertProperty("to_be_deleted", "xxx", null, null);
    // project - do not delete this project property that has the same key
    long id3 = insertProperty("to_be_deleted", "new_project", "to_be_deleted", null);
    // user
    long id4 = insertProperty("user.key", "new_user", null, 100);

    underTest.deleteGlobalProperty("to_be_deleted");

    assertThatPropertiesRow(id1)
      .hasKey("global.key")
      .hasNoUserId()
      .hasNoComponentUuid()
      .hasTextValue("new_global");
    assertThatPropertiesRow(id2)
      .doesNotExist();
    assertThatPropertiesRow("to_be_deleted", null, null)
      .doesNotExist();
    assertThatPropertiesRow(id3)
      .hasKey("to_be_deleted")
      .hasComponentUuid("to_be_deleted")
      .hasNoUserId()
      .hasTextValue("new_project");
    assertThatPropertiesRow(id4)
      .hasKey("user.key")
      .hasNoComponentUuid()
      .hasUserId(100)
      .hasTextValue("new_user");
  }

  @Test
  public void delete_by_organization_and_user() {
    OrganizationDto organization = db.organizations().insert();
    OrganizationDto anotherOrganization = db.organizations().insert();
    ComponentDto project = db.components().insertPrivateProject(organization);
    ComponentDto anotherProject = db.components().insertPrivateProject(anotherOrganization);
    UserDto user = db.users().insertUser();
    UserDto anotherUser = db.users().insertUser();
    insertProperty("KEY_11", "VALUE", project.uuid(), user.getId());
    insertProperty("KEY_12", "VALUE", project.uuid(), user.getId());
    insertProperty("KEY_11", "VALUE", project.uuid(), anotherUser.getId());
    insertProperty("KEY_11", "VALUE", anotherProject.uuid(), user.getId());

    underTest.deleteByOrganizationAndUser(session, organization.getUuid(), user.getId());

    assertThat(dbClient.propertiesDao().selectByQuery(PropertyQuery.builder().setComponentUuid(project.uuid()).build(), session))
      .hasSize(1)
      .extracting(PropertyDto::getUserId).containsOnly(anotherUser.getId());
    assertThat(dbClient.propertiesDao().selectByQuery(PropertyQuery.builder().setComponentUuid(anotherProject.uuid()).build(), session))
      .extracting(PropertyDto::getUserId)
      .hasSize(1).containsOnly(user.getId());
  }

  @Test
  public void delete_by_organization_and_matching_login() {
    OrganizationDto organization = db.organizations().insert();
    OrganizationDto anotherOrganization = db.organizations().insert();
    ComponentDto project = db.components().insertPrivateProject(organization);
    ComponentDto anotherProject = db.components().insertPrivateProject(anotherOrganization);
    UserDto user = db.users().insertUser();
    UserDto anotherUser = db.users().insertUser();
    insertProperty("KEY_11", user.getLogin(), project.uuid(), null);
    insertProperty("KEY_12", user.getLogin(), project.uuid(), null);
    insertProperty("KEY_11", anotherUser.getLogin(), project.uuid(), null);
    insertProperty("KEY_11", user.getLogin(), anotherProject.uuid(), null);

    underTest.deleteByOrganizationAndMatchingLogin(session, organization.getUuid(), user.getLogin(), newArrayList("KEY_11", "KEY_12"));

    assertThat(dbClient.propertiesDao().selectByQuery(PropertyQuery.builder().setComponentUuid(project.uuid()).build(), session))
      .hasSize(1)
      .extracting(PropertyDto::getValue).containsOnly(anotherUser.getLogin());
    assertThat(dbClient.propertiesDao().selectByQuery(PropertyQuery.builder().setComponentUuid(anotherProject.uuid()).build(), session))
      .extracting(PropertyDto::getValue)
      .hasSize(1).containsOnly(user.getLogin());
  }

  @Test
  public void delete_by_key_and_value() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto anotherProject = db.components().insertPrivateProject();
    insertProperty("KEY", "VALUE", null, null);
    insertProperty("KEY", "VALUE", project.uuid(), null);
    insertProperty("KEY", "VALUE", null, 100);
    insertProperty("KEY", "VALUE", project.uuid(), 100);
    insertProperty("KEY", "VALUE", anotherProject.uuid(), null);
    // Should not be removed
    insertProperty("KEY", "ANOTHER_VALUE", null, null);
    insertProperty("ANOTHER_KEY", "VALUE", project.uuid(), 100);

    underTest.deleteByKeyAndValue(session, "KEY", "VALUE");
    db.commit();

    assertThat(db.select("select prop_key as \"key\", text_value as \"value\", component_uuid as \"projectUuid\", user_id as \"userId\" from properties"))
      .extracting((row) -> row.get("key"), (row) -> row.get("value"), (row) -> row.get("projectUuid"), (row) -> row.get("userId"))
      .containsOnly(tuple("KEY", "ANOTHER_VALUE", null, null), tuple("ANOTHER_KEY", "VALUE", project.uuid(), 100L));
  }

  @Test
  public void saveGlobalProperties_insert_property_if_does_not_exist_in_db() {
    underTest.saveGlobalProperties(mapOf(
      "null_value_property", null,
      "empty_value_property", "",
      "text_value_property", "dfdsfsd",
      "4000_char_value_property", VALUE_SIZE_4000,
      "clob_value_property", VALUE_SIZE_4001));

    assertThatPropertiesRow("null_value_property")
      .hasNoComponentUuid()
      .hasNoUserId()
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 2);
    assertThatPropertiesRow("empty_value_property")
      .hasNoComponentUuid()
      .hasNoUserId()
      .isEmpty()
      .hasCreatedAt(INITIAL_DATE + 3);
    assertThatPropertiesRow("text_value_property")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("dfdsfsd")
      .hasCreatedAt(INITIAL_DATE + 4);
    assertThatPropertiesRow("4000_char_value_property")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue(VALUE_SIZE_4000)
      .hasCreatedAt(INITIAL_DATE + 5);
    assertThatPropertiesRow("clob_value_property")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasClobValue(VALUE_SIZE_4001)
      .hasCreatedAt(INITIAL_DATE + 6);
  }

  @Test
  public void saveGlobalProperties_delete_and_insert_new_value_when_property_exists_in_db() {
    long id = insertProperty("to_be_updated", "old_value", null, null);

    underTest.saveGlobalProperties(ImmutableMap.of("to_be_updated", "new value"));

    assertThatPropertiesRow(id)
      .doesNotExist();

    assertThatPropertiesRow("to_be_updated")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("new value")
      .hasCreatedAt(INITIAL_DATE + 3);
  }

  private static Map<String, String> mapOf(String... values) {
    // use LinkedHashMap to keep order of array
    Map<String, String> res = new LinkedHashMap<>(values.length / 2);
    Iterator<String> iterator = Arrays.asList(values).iterator();
    while (iterator.hasNext()) {
      res.put(iterator.next(), iterator.next());
    }
    return res;
  }

  @Test
  public void renamePropertyKey_updates_global_component_and_user_properties() {
    long id1 = insertProperty("foo", "bar", null, null);
    long id2 = insertProperty("old_name", "doc1", null, null);
    long id3 = insertProperty("old_name", "doc2", "15", null);
    long id4 = insertProperty("old_name", "doc3", "16", null);
    long id5 = insertProperty("old_name", "doc4", null, 100);
    long id6 = insertProperty("old_name", "doc5", null, 101);

    underTest.renamePropertyKey("old_name", "new_name");

    assertThatPropertiesRow(id1)
      .hasKey("foo")
      .hasNoUserId()
      .hasNoComponentUuid()
      .hasTextValue("bar")
      .hasCreatedAt(INITIAL_DATE + 2);
    assertThatPropertiesRow(id2)
      .hasKey("new_name")
      .hasNoComponentUuid()
      .hasNoUserId()
      .hasTextValue("doc1")
      .hasCreatedAt(INITIAL_DATE + 3);
    assertThatPropertiesRow(id3)
      .hasKey("new_name")
      .hasComponentUuid("15")
      .hasNoUserId()
      .hasTextValue("doc2")
      .hasCreatedAt(INITIAL_DATE + 4);
    assertThatPropertiesRow(id4)
      .hasKey("new_name")
      .hasComponentUuid("16")
      .hasNoUserId()
      .hasTextValue("doc3")
      .hasCreatedAt(INITIAL_DATE + 5);
    assertThatPropertiesRow(id5)
      .hasKey("new_name")
      .hasNoComponentUuid()
      .hasUserId(100)
      .hasTextValue("doc4")
      .hasCreatedAt(INITIAL_DATE + 6);
    assertThatPropertiesRow(id6)
      .hasKey("new_name")
      .hasNoComponentUuid()
      .hasUserId(101)
      .hasTextValue("doc5")
      .hasCreatedAt(INITIAL_DATE + 7);
  }

  @Test
  public void rename_to_same_key_has_no_effect() {
    long id = insertProperty("foo", "bar", null, null);

    assertThatPropertiesRow(id)
      .hasCreatedAt(INITIAL_DATE + 2);

    underTest.renamePropertyKey("foo", "foo");

    assertThatPropertiesRow(id)
      .hasKey("foo")
      .hasNoUserId()
      .hasNoComponentUuid()
      .hasTextValue("bar")
      .hasCreatedAt(INITIAL_DATE + 2);
  }

  @Test
  public void should_not_rename_with_empty_key() {
    thrown.expect(IllegalArgumentException.class);
    underTest.renamePropertyKey("foo", "");
  }

  @Test
  public void should_not_rename_an_empty_key() {
    thrown.expect(IllegalArgumentException.class);
    underTest.renamePropertyKey(null, "foo");
  }

  private PropertyDto findByKey(List<PropertyDto> properties, String key) {
    for (PropertyDto property : properties) {
      if (key.equals(property.getKey())) {
        return property;
      }
    }
    return null;
  }

  private void insertProperties(PropertyDto... properties) {
    for (PropertyDto propertyDto : properties) {
      underTest.saveProperty(session, propertyDto);
    }
    session.commit();
  }

  private long insertProperty(String key, @Nullable String value, @Nullable String componentUuid, @Nullable Integer userId) {
    PropertyDto dto = new PropertyDto().setKey(key)
      .setComponentUuid(componentUuid)
      .setUserId(userId)
      .setValue(value);
    db.properties().insertProperty(dto);

    return (long) db.selectFirst(session, "select id as \"id\" from properties" +
      " where prop_key='" + key + "'" +
      " and user_id" + (userId == null ? " is null" : "='" + userId + "'") +
      " and component_uuid" + (componentUuid == null ? " is null" : "='" + componentUuid + "'")).get("id");
  }

  private ComponentDto insertPrivateProject(String projectKey) {
    return db.components().insertPrivateProject(db.getDefaultOrganization(), t -> t.setDbKey(projectKey));
  }

  private static Consumer<UserDto> withEmail(String login) {
    return u -> u.setLogin(login).setEmail(emailOf(login));
  }

  private static String emailOf(String login) {
    return login + "@foo";
  }

  private static Consumer<UserDto> noEmail(String login) {
    return u -> u.setLogin(login).setEmail(null);
  }

  private static String propertyKeyOf(String dispatcherKey, String channelKey) {
    return String.format("notification.%s.%s", dispatcherKey, channelKey);
  }

  private PropertiesRowAssert assertThatPropertiesRow(String key, @Nullable Integer userId, @Nullable String componentUuid) {
    return new PropertiesRowAssert(db, key, userId, componentUuid);
  }

  private PropertiesRowAssert assertThatPropertiesRow(String key) {
    return new PropertiesRowAssert(db, key);
  }

  private PropertiesRowAssert assertThatPropertiesRow(long id) {
    return new PropertiesRowAssert(db, id);
  }

}
