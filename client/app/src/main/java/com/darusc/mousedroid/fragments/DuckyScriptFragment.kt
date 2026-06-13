package com.darusc.mousedroid.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.darusc.mousedroid.databinding.FragmentDuckyScriptBinding
import com.darusc.mousedroid.viewmodels.DuckyScriptRunnerViewModel
import com.darusc.mousedroid.viewmodels.DuckyScriptRunnerViewModel.State
import kotlinx.coroutines.launch

class DuckyScriptFragment : Fragment() {

    private var _binding: FragmentDuckyScriptBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DuckyScriptRunnerViewModel by activityViewModels()

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val content = requireContext().contentResolver.openInputStream(it)?.readBytes()?.decodeToString()
                if (content != null) {
                    binding.scriptEditor.setText(content)
                }
            } catch (e: Exception) {
                binding.statusText.text = "Error loading file: ${e.message}"
            }
        }
    }

    private val exportScriptLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            try {
                val content = binding.scriptEditor.text?.toString() ?: ""
                requireContext().contentResolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray())
                }
                binding.statusText.text = "Script exported"
            } catch (e: Exception) {
                binding.statusText.text = "Export failed: ${e.message}"
            }
        }
    }

    private val exportLogsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            try {
                val content = binding.debugConsole.text?.toString() ?: ""
                requireContext().contentResolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray())
                }
                binding.statusText.text = "Logs exported"
            } catch (e: Exception) {
                binding.statusText.text = "Export failed: ${e.message}"
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuckyScriptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupExecuteButton()
        setupLoadButton()
        setupExportScriptButton()
        setupClearLogsButton()
        setupExportLogsButton()
        observeState()
        observeLogs()
    }

    private fun setupExecuteButton() {
        binding.executeButton.setOnClickListener {
            val script = binding.scriptEditor.text?.toString() ?: ""
            viewModel.compileAndRun(script)
        }
    }

    private fun setupLoadButton() {
        binding.loadScriptButton.setOnClickListener {
            getContent.launch("text/plain")
        }
    }

    private fun setupExportScriptButton() {
        binding.exportScriptButton.setOnClickListener {
            exportScriptLauncher.launch("script.txt")
        }
    }

    private fun setupClearLogsButton() {
        binding.clearLogsButton.setOnClickListener {
            viewModel.clearLogs()
        }
    }

    private fun setupExportLogsButton() {
        binding.exportLogsButton.setOnClickListener {
            exportLogsLauncher.launch("duckyscript_logs.txt")
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        updateUIForState(state)
                    }
                }
            }
        }
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logText.collect { text ->
                        binding.debugConsole.text = text
                        binding.debugConsole.post {
                            binding.debugConsole.scrollTo(
                                0, binding.debugConsole.layout?.getLineTop(binding.debugConsole.lineCount)
                                    ?.coerceAtLeast(0) ?: 0
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateUIForState(state: State) {
        when (state) {
            State.Idle -> {
                binding.progressBar.visibility = View.GONE
                binding.executeButton.isEnabled = true
                binding.statusText.text = "Ready"
            }
            State.Executing -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.executeButton.isEnabled = false
                binding.statusText.text = "Executing..."
            }
            State.Complete -> {
                binding.progressBar.visibility = View.GONE
                binding.executeButton.isEnabled = true
                binding.statusText.text = "Completed"
            }
            is State.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.executeButton.isEnabled = true
                binding.statusText.text = "Error: ${state.message}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
