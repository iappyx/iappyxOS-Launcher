/*
 * MIT License - Copyright (c) 2026 iappyx
 * Plan A Phase 4a — AI keys + model picker moved out of the monolithic
 * SettingsActivity. Owns the SecureStore-backed Anthropic key, model
 * dropdown (Create + Iterate), GitHub token, and the model-catalog
 * fetch. Auto-saves on pause; explicit "Save credentials" button feeds
 * back via toast.
 */
package com.iappyx.launcher

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.iappyx.launcher.ai.AiException
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.ModelCatalog
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.widget.showThemed

class AISettingsActivity : AppCompatActivity() {

    @Volatile private var destroyed = false

    private lateinit var credentialStore: SecureStore
    private lateinit var keyField: EditText
    private lateinit var modelField: MaterialAutoCompleteTextView
    private lateinit var iterateModelField: MaterialAutoCompleteTextView
    private lateinit var modelStatusLabel: TextView
    private lateinit var modelRefreshLink: TextView
    private lateinit var githubField: EditText
    private var anthropicKeyRemoveLink: TextView? = null
    private var githubTokenRemoveLink: TextView? = null
    private var lastSavedKey: String? = null
    private var lastSavedModel: String? = null
    private var lastSavedIterateModel: String? = null
    private var lastSavedGithub: String? = null
    private var selectedCreateModelId: String = SecureStore.DEFAULT_MODEL
    private var selectedIterateModelId: String = SecureStore.DEFAULT_ITERATE_MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_settings)
        SettingsScaffold.attach(this, getString(R.string.settings_ai_section))

        credentialStore = SecureStore(this)
        val store = credentialStore
        keyField = findViewById(R.id.anthropic_key)
        modelField = findViewById(R.id.anthropic_model)
        iterateModelField = findViewById(R.id.iterate_model)
        modelStatusLabel = findViewById(R.id.model_status)
        modelRefreshLink = findViewById(R.id.model_refresh)
        val removeKey = findViewById<TextView>(R.id.anthropic_key_remove)

        keyField.setText(store.anthropicKey.orEmpty())
        selectedCreateModelId = store.anthropicModel
        selectedIterateModelId = store.iterateModel
        modelField.setText(selectedCreateModelId, false)
        iterateModelField.setText(selectedIterateModelId, false)
        lastSavedKey = store.anthropicKey
        lastSavedModel = selectedCreateModelId
        lastSavedIterateModel = selectedIterateModelId

        listOf(modelField, iterateModelField).forEach { field ->
            field.threshold = 0
            field.setOnClickListener { field.showDropDown() }
        }
        modelRefreshLink.setOnClickListener {
            ModelCatalog.clear(this)
            loadModels(force = true)
        }
        loadModels(force = false)

        anthropicKeyRemoveLink = removeKey
        refreshAnthropicRemoveVisibility()
        removeKey.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_remove_api_key_title)
                .setMessage(R.string.settings_remove_api_key_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove) { _, _ ->
                    store.anthropicKey = null
                    lastSavedKey = null
                    keyField.setText("")
                    refreshAnthropicRemoveVisibility()
                    ModelCatalog.clear(this)
                    loadModels(force = false)
                    Toast.makeText(this, R.string.settings_api_key_removed_toast, Toast.LENGTH_SHORT).show()
                }
                .showThemed()
        }

        githubField = findViewById(R.id.github_token)
        val removeGithub = findViewById<TextView>(R.id.github_token_remove)
        githubField.setText(store.githubToken.orEmpty())
        lastSavedGithub = store.githubToken
        githubTokenRemoveLink = removeGithub
        refreshGithubRemoveVisibility()
        removeGithub.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_remove_github_token_title)
                .setMessage(R.string.settings_remove_github_token_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove) { _, _ ->
                    store.githubToken = null
                    lastSavedGithub = null
                    githubField.setText("")
                    refreshGithubRemoveVisibility()
                    Toast.makeText(this, R.string.settings_github_token_removed_toast, Toast.LENGTH_SHORT).show()
                }
                .showThemed()
        }

        findViewById<Button>(R.id.save_btn).setOnClickListener {
            persistCredentialsIfChanged(forceToast = true)
            refreshAnthropicRemoveVisibility()
            refreshGithubRemoveVisibility()
        }
    }

    override fun onPause() {
        // Safety net — backing out of Settings without tapping Save still
        // persists whatever's typed. Silent (no toast) to avoid surprising
        // the user who's just navigating away.
        persistCredentialsIfChanged(forceToast = false)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun loadModels(force: Boolean) {
        val key = credentialStore.anthropicKey?.takeIf { it.isNotBlank() }
        if (key == null) {
            modelStatusLabel.setText(R.string.settings_models_no_key)
            applyModelAdapter(emptyList())
            return
        }
        modelStatusLabel.setText(R.string.settings_models_loading)
        Thread {
            val result = try {
                Result.success(ModelCatalog.fetchOrCached(this, key, force))
            } catch (e: AiException) {
                Result.failure<List<AiService.ModelInfo>>(e)
            } catch (t: Throwable) {
                Result.failure<List<AiService.ModelInfo>>(t)
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.onSuccess { models ->
                    modelStatusLabel.text = getString(
                        R.string.settings_models_count_format, models.size,
                    )
                    applyModelAdapter(models)
                }.onFailure {
                    modelStatusLabel.setText(R.string.settings_models_error)
                    applyModelAdapter(emptyList())
                }
            }
        }.start()
    }

    private fun applyModelAdapter(models: List<AiService.ModelInfo>) {
        listOf(
            Triple(modelField, selectedCreateModelId) { id: String ->
                selectedCreateModelId = id
            },
            Triple(iterateModelField, selectedIterateModelId) { id: String ->
                selectedIterateModelId = id
            },
        ).forEach { (field, currentId, setter) ->
            val items = mutableListOf<DropdownItem>()
            val knownIds = models.map { it.id }.toSet()
            if (currentId.isNotBlank() && currentId !in knownIds) {
                items.add(DropdownItem(
                    id = currentId,
                    label = "⚠ $currentId (not available)",
                    isDeprecated = true,
                ))
            }
            for (m in models) {
                items.add(DropdownItem(
                    id = m.id,
                    label = "${m.displayName} — ${m.id}",
                    isDeprecated = false,
                ))
            }
            val adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_dropdown_item_1line,
                items.map { it.label },
            )
            field.setAdapter(adapter)
            field.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
                if (pos in items.indices) {
                    val picked = items[pos]
                    setter(picked.id)
                    field.setText(displayLabelFor(picked.id, models), false)
                }
            }
            field.setText(displayLabelFor(currentId, models), false)
        }
    }

    private fun displayLabelFor(id: String, models: List<AiService.ModelInfo>): String {
        if (id.isBlank()) return ""
        val match = models.firstOrNull { it.id == id }
        return if (match != null) match.displayName else "⚠ $id"
    }

    private data class DropdownItem(
        val id: String,
        val label: String,
        val isDeprecated: Boolean,
    )

    private fun refreshAnthropicRemoveVisibility() {
        anthropicKeyRemoveLink?.visibility =
            if (credentialStore.anthropicKey.isNullOrBlank()) android.view.View.GONE
            else android.view.View.VISIBLE
    }

    private fun refreshGithubRemoveVisibility() {
        githubTokenRemoveLink?.visibility =
            if (credentialStore.githubToken.isNullOrBlank()) android.view.View.GONE
            else android.view.View.VISIBLE
    }

    private fun persistCredentialsIfChanged(forceToast: Boolean): Boolean {
        if (!::credentialStore.isInitialized) return false
        val key = keyField.text.toString().trim().ifBlank { null }
        val model = selectedCreateModelId.ifBlank { SecureStore.DEFAULT_MODEL }
        val iterateModel = selectedIterateModelId.ifBlank { SecureStore.DEFAULT_ITERATE_MODEL }
        val github = githubField.text.toString().trim().ifBlank { null }
        var changed = false
        if (key != lastSavedKey) {
            credentialStore.anthropicKey = key
            lastSavedKey = key
            changed = true
            ModelCatalog.clear(this)
            loadModels(force = true)
        }
        if (model != lastSavedModel) {
            credentialStore.anthropicModel = model
            lastSavedModel = model
            changed = true
        }
        if (iterateModel != lastSavedIterateModel) {
            credentialStore.iterateModel = iterateModel
            lastSavedIterateModel = iterateModel
            changed = true
        }
        if (github != lastSavedGithub) {
            credentialStore.githubToken = github
            lastSavedGithub = github
            changed = true
        }
        if (forceToast || changed) {
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
        return changed
    }
}
