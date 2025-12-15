package com.example.aichallenge.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun UserProfilesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: UserProfilesViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }
    BackHandler {
        if (editingProfile != null) {
            editingProfile = null
        } else {
            onBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Профили пользователя",
                style = MaterialTheme.typography.headlineSmall
            )
            TextButton(onClick = {
                if (editingProfile != null) {
                    editingProfile = null
                } else {
                    onBack()
                }
            }) {
                Text(if (editingProfile == null) "Назад" else "К списку")
            }
        }

        state.selectedProfile?.let { profile ->
            CurrentProfileSummary(profile = profile)
            Spacer(Modifier.height(16.dp))
        }

        if (editingProfile == null) {
            UserProfilesList(
                state = state,
                onSelect = vm::selectUser,
                onEdit = { editingProfile = it },
                onAdd = { editingProfile = vm.newProfile() },
                modifier = Modifier.weight(1f, fill = true)
            )
        } else {
            val profile = editingProfile!!
            val isNew = state.profiles.none { it.id == profile.id }
            UserProfileForm(
                profile = profile,
                isNew = isNew,
                onSave = { updated ->
                    vm.saveProfile(updated)
                    editingProfile = null
                },
                onCancel = { editingProfile = null },
                onDelete = { toDelete ->
                    vm.deleteProfile(toDelete.id)
                    editingProfile = null
                },
                modifier = Modifier.weight(1f, fill = true)
            )
        }
    }
}

@Composable
private fun CurrentProfileSummary(profile: UserProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Текущий пользователь", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(profile.name.ifBlank { "Без имени" })
                    val location = listOf(profile.city, profile.country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (location.isNotBlank()) {
                        append(" · ").append(location)
                    }
                }
            )
            if (profile.preferredLanguage.isNotBlank()) {
                Text("Язык: ${profile.preferredLanguage}")
            }
            if (profile.timezone.isNotBlank()) {
                Text("Часовой пояс: ${profile.timezone}")
            }
            if (profile.answerTone.isNotBlank() || profile.answerFormat.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(
                        profile.answerTone.ifBlank { null },
                        profile.answerFormat.ifBlank { null }
                    ).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun UserProfilesList(
    state: UserProfilesState,
    onSelect: (String) -> Unit,
    onEdit: (UserProfile) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasProfiles) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Пользователи не сохранены",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onAdd() }) {
                Text("Добавить пользователя")
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        state.profiles.forEach { profile ->
            ProfileEntry(
                profile = profile,
                isSelected = profile.id == state.selectedUserId,
                onSelect = { onSelect(profile.id) },
                onEdit = { onEdit(profile) }
            )
            Spacer(Modifier.height(12.dp))
        }
        Divider()
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onAdd() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Добавить пользователя")
        }
    }
}

@Composable
private fun ProfileEntry(
    profile: UserProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = profile.name.ifBlank { "Без имени" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    val subtitle = buildString {
                        if (profile.preferredLanguage.isNotBlank()) {
                            append(profile.preferredLanguage)
                        }
                        val location = listOf(profile.city, profile.country)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                        if (location.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(location)
                        }
                    }
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (isSelected) {
                    Text(
                        text = "Текущий",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onSelect, modifier = Modifier.weight(1f)) {
                    Text(if (isSelected) "Выбран" else "Сделать текущим")
                }
                TextButton(onClick = onEdit) {
                    Text("Редактировать")
                }
            }
        }
    }
}

@Composable
private fun UserProfileForm(
    profile: UserProfile,
    isNew: Boolean,
    onSave: (UserProfile) -> Unit,
    onCancel: () -> Unit,
    onDelete: (UserProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var language by remember(profile.id) { mutableStateOf(profile.preferredLanguage) }
    var country by remember(profile.id) { mutableStateOf(profile.country) }
    var city by remember(profile.id) { mutableStateOf(profile.city) }
    var timezone by remember(profile.id) { mutableStateOf(profile.timezone) }
    var answerTone by remember(profile.id) { mutableStateOf(profile.answerTone) }
    var answerFormat by remember(profile.id) { mutableStateOf(profile.answerFormat) }
    var answerLength by remember(profile.id) { mutableStateOf(profile.answerLength) }
    var expertise by remember(profile.id) { mutableStateOf(profile.expertiseDomain) }
    var interests by remember(profile.id) { mutableStateOf(profile.interests) }
    var notes by remember(profile.id) { mutableStateOf(profile.notes) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
    ) {
        Text(
            text = if (isNew) "Новый пользователь" else "Редактирование пользователя",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))
        ProfileTextField("Имя", name) { name = it }
        ProfileTextField("Предпочтительный язык", language) { language = it }
        ProfileTextField("Страна", country) { country = it }
        ProfileTextField("Город", city) { city = it }
        ProfileTextField("Часовой пояс (UTC+3, Europe/Moscow ...)", timezone) { timezone = it }
        ProfileTextField("Тон ответа (дружелюбный, деловой...)", answerTone) { answerTone = it }
        ProfileTextField("Формат ответа (пули, markdown, тезисы...)", answerFormat) { answerFormat = it }
        ProfileTextField("Желаемая длина ответа", answerLength) { answerLength = it }
        ProfileTextField("Доменные знания / экспертиза", expertise) { expertise = it }
        ProfileTextField("Темы / интересы", interests) { interests = it }
        ProfileTextField("Доп. инструкции / табу", notes) { notes = it }
        Spacer(Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    onSave(
                        profile.copy(
                            name = name,
                            preferredLanguage = language,
                            country = country,
                            city = city,
                            timezone = timezone,
                            answerTone = answerTone,
                            answerFormat = answerFormat,
                            answerLength = answerLength,
                            expertiseDomain = expertise,
                            interests = interests,
                            notes = notes,
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Сохранить")
            }
            TextButton(onClick = onCancel) {
                Text("Отмена")
            }
        }
        if (!isNew) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onDelete(profile) }) {
                Text("Удалить профиль")
            }
        }
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}
