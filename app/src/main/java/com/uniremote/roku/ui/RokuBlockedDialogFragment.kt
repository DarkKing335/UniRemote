package com.uniremote.roku.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.uniremote.roku.R
import com.uniremote.roku.control.RokuIRController
import com.uniremote.roku.databinding.DialogRokuBlockedBinding

/**
 * DialogFragment that guides the user to fix "Network Access: Disabled" on Roku TV.
 * Provides a fallback option to use IR mode if supported by hardware.
 */
class RokuBlockedDialogFragment : DialogFragment() {

    interface Listener {
        fun onSwitchToIrMode()
        fun onDismiss()
    }

    private var _binding: DialogRokuBlockedBinding? = null
    private val binding get() = _binding!!
    
    var listener: Listener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRokuBlockedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val irController = RokuIRController(requireContext())
        val isIrSupported = irController.isIrSupported()

        // Sync button visibility with hardware capability
        binding.btnIrMode.visibility = if (isIrSupported) View.VISIBLE else View.GONE

        binding.btnIrMode.setOnClickListener {
            listener?.onSwitchToIrMode()
            dismiss()
        }

        binding.btnOk.setOnClickListener {
            listener?.onDismiss()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RokuBlockedDialog"
        
        fun newInstance(): RokuBlockedDialogFragment {
            return RokuBlockedDialogFragment()
        }
    }
}
