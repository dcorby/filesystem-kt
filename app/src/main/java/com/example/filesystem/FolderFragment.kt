package com.example.filesystem

import android.app.ActionBar
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.RecyclerView
import com.example.filesystem.actions.*
import com.example.filesystem.databinding.FragmentFolderBinding
import java.util.*
import kotlin.collections.HashMap

const val OPEN_DOCUMENT_TREE_REQUEST_CODE = 1
const val AUTHORITY = "com.android.externalstorage.documents"

/*
  https://github.com/material-components/material-components-android/issues/2291
 */

interface DialogCallback {
    fun onDialogClickYes(uri: Uri)
    fun onDialogClickNo()
    fun onDismiss()
}

class FolderFragment : Fragment(), DialogCallback {

    private var _binding: FragmentFolderBinding? = null
    private val binding get() = _binding!!
    private val sanFilesViewModel: SanFilesViewModel by viewModels()
    lateinit var sanFilesAdapter: SanFilesAdapter
    lateinit var fragmentStr: String
    lateinit var fragmentUri: Uri
    lateinit var fragmentDocId: String
    lateinit var tracker: SelectionTracker<String>
    lateinit var liveData: LiveData<MutableList<SanFile>>
    lateinit var receiver: MainReceiver
    private var currentAction: String? = null

    // popup and alert windows
    lateinit var popup: PopupWindow
    lateinit var prompt: PopupWindow
    //lateinit var dialog: MyDialogFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentFolderBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sanFilesAdapter = SanFilesAdapter { sanFile -> adapterOnClick(sanFile) }
        val recyclerView: RecyclerView = binding.recyclerView
        recyclerView.adapter = sanFilesAdapter
        observeCurrent(fragmentDocId)

        tracker = SelectionTracker.Builder<String>(
            "selectionItem",
            binding.recyclerView,
            ItemsKeyProvider(sanFilesAdapter),
            ItemsDetailsLookup(binding.recyclerView),
            StorageStrategy.createStringStorage()
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()

        sanFilesAdapter.tracker = tracker
        sanFilesAdapter.initTracker()

        if (savedInstanceState != null) {
            tracker.onRestoreInstanceState(savedInstanceState)
        }

        // Check whether we have an active copy or move
        if (receiver.getActionState("copy", "sourceUri") != null) {
            binding.toggleGroup.check(R.id.action_copy)
        }
        if (receiver.getActionState("move", "sourceUri") != null) {
            binding.toggleGroup.check(R.id.action_move)
        }

        // Set the path parts
        val pathParts = Utils.getPathPartsFromDocId(fragmentDocId)
        binding.pathParts.removeAllViews()
        for (pathPart in pathParts) {
            val textView = layoutInflater.inflate(R.layout.path_part, null) as TextView
            textView.text = pathPart
            val params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            textView.setLayoutParams(params)
            binding.pathParts.addView(textView)
        }

        // Actions
        val actions = Actions(this)

        // Create File
        fun createFile() {
            val action = actions.get("createFile") as CreateFile
            action.handle(requireActivity(), binding, fragmentUri, fragmentDocId) {
                observeCurrent(fragmentDocId)
            }
        }
        binding.actionCreateFile.setOnClickListener {
            currentAction = "createFile"
            createFile()
            currentAction = null
        }
        fun createFolder() {
            val action = actions.get("createFolder") as CreateFolder
            action.handle(binding, fragmentUri, fragmentDocId) {
                observeCurrent(fragmentDocId)
            }
        }
        // Create Folder
        binding.actionCreateFolder.setOnClickListener {
            currentAction = "createFolder"
            createFolder()
            currentAction = null
        }
        // Open
        fun open() {
            val action = actions.get("open") as Open
            action.handle(requireActivity(), binding, tracker.selection, fragmentUri)
        }
        binding.actionOpen.setOnClickListener {
            currentAction = "open"
            open()
            currentAction = null
        }
        // Rename
        fun rename() {
            val action = actions.get("rename") as Rename
            action.handle(requireActivity(), binding, tracker.selection, fragmentUri) {
                observeCurrent(fragmentDocId)
            }
        }
        binding.actionRename.setOnClickListener {
            currentAction = "rename"
            rename()
            currentAction = null
        }
        // Copy
        fun copy() {
            currentAction = "copy"
            val action = actions.get("copy") as Copy
            val copied = action.handle(requireActivity(), binding, tracker.selection, fragmentUri, fragmentDocId,
                fun() {
                    currentAction = null
                    binding.close.setOnClickListener(null)
                    receiver.setActionState("copy", "sourceUri", null)
                    receiver.setActionState("copy", "filename", null)
                })
            if (copied) {
                observeCurrent(fragmentDocId)
            }
        }
        binding.actionCopy.setOnClickListener {
            copy()
        }
        // Move
        fun move() {
            val action = actions.get("move") as Move
            val success = action.handle(requireActivity(), binding, tracker.selection, fragmentUri, fragmentDocId)
            if (success) {
                observeCurrent(fragmentDocId)
            }
        }
        binding.actionMove.setOnClickListener {
            currentAction = "move"
            move()
            currentAction = null
        }
        // Delete
        fun delete() {
            currentAction = "delete"
            val action = actions.get("delete") as Delete
            action.handle(requireActivity(), binding, tracker.selection, fragmentUri,
                fun(success) {
                    if (success) {
                        observeCurrent(fragmentDocId)
                    }
                    currentAction = null
                })
        }
        binding.actionDelete.setOnClickListener {
            delete()
        }

        // If we have savedInstanceState, check for a currentAction and initiate it
        // https://stackoverflow.com/questions/69622835/how-to-call-a-function-in-kotlin-from-a-string-name
        val actionFuncs = listOf(::copy, ::createFile, ::createFolder, ::delete, ::move, ::open, ::rename).associateBy { it.name }
        if (savedInstanceState != null) {
            if (savedInstanceState.getString("currentAction") != null) {
                val currentAction = savedInstanceState.getString("currentAction")!!
                val actionState = savedInstanceState.getSerializable(currentAction) as HashMap<String, String>
                for ((key, value) in actionState) {
                    receiver.setActionState(currentAction, key, value)
                }
                actionFuncs[currentAction]?.invoke()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = (activity as MainReceiver)

        val settings: SharedPreferences = requireActivity().getSharedPreferences("UserInfo", 0)
        fragmentStr = arguments?.getString("fragmentUri", settings.getString("root", null))!!
        fragmentUri = fragmentStr.toUri()
        fragmentDocId = arguments?.getString("fragmentDocId", DocumentsContract.getTreeDocumentId(fragmentUri))!!

        val uriPermissions = requireActivity().contentResolver.persistedUriPermissions
        var havePermissions = false
        for (uriPermission in uriPermissions) {
            if (uriPermission.isReadPermission && uriPermission.isWritePermission) {
                if (Utils.decode(fragmentStr).contains(Utils.decode(uriPermission.uri.toString()))) {
                    havePermissions = true
                }
            }
        }

        // If user lost permissions somehow, regain them
        if (!havePermissions) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, "")
            startActivityForResult(intent, OPEN_DOCUMENT_TREE_REQUEST_CODE)
        }
    }

    private fun observeCurrent(docId: String?) {
        val mutableList: MutableList<SanFile> = Utils.getChildren(requireActivity(), fragmentUri, docId)

        // Observe the current directory
        liveData = sanFilesViewModel.initSanFiles(mutableList)
        liveData.observe(viewLifecycleOwner, Observer { updatedList ->
            // onChange(): https://developer.android.com/reference/androidx/lifecycle/Observer
            sanFilesAdapter.submitList(updatedList as MutableList<SanFile>)
        })
    }

    // Not currently used, but available from adapter
    private fun adapterOnClick(sanFile: SanFile) {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        if (this::prompt.isInitialized && prompt.isShowing) {
            prompt.dismiss()
        }
        if (this::popup.isInitialized && popup.isShowing) {
            popup.dismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check for active copy
        if (receiver.getActionState("copy", "sourceUri") != null) {
            binding.toggleGroup.check(R.id.action_copy)
        } else {
            binding.toggleGroup.uncheck(R.id.action_copy)
        }
        // Check for active move
        if (receiver.getActionState("move", "sourceUri") != null) {
            binding.toggleGroup.check(R.id.action_move)
        } else {
            binding.toggleGroup.uncheck(R.id.action_move)
        }

        // Dismiss the delete dialog fragment if necessary
        // Provides a smoother UI effect here
        // https://stackoverflow.com/questions/9325238/proper-way-of-dismissing-dialogfragment-while-application-is-in-background
        val fragmentManager: FragmentManager = parentFragmentManager
        val dialogFragment: DialogFragment? = fragmentManager.findFragmentByTag("dialog") as DialogFragment?
        dialogFragment?.dismiss()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OPEN_DOCUMENT_TREE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data!!
            fragmentUri = uri
            val contentResolver = requireActivity().contentResolver
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val settings: SharedPreferences = requireActivity().getSharedPreferences("UserInfo", 0)
            val editor = settings.edit()
            editor.putString("root", Utils.decode(uri.toString()))
            editor.commit()
            observeCurrent(null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::tracker.isInitialized) {
            tracker.onSaveInstanceState(outState)
        }
        // save ActionState data
        if (currentAction != null) {
            outState.putString("currentAction", currentAction)
            outState.putSerializable(currentAction, receiver.getActionState(currentAction!!))
        }
    }

    fun getPopupWindow(type: String) : PopupWindow {
        // popup
        if (type == "popup") {
            val layout = layoutInflater.inflate(R.layout.popup, binding.fragmentParent, false)
            popup = PopupWindow(
                layout,
                ActionBar.LayoutParams.MATCH_PARENT,
                ActionBar.LayoutParams.MATCH_PARENT,
                true
            )
            popup.showAtLocation(layout, Gravity.CENTER, 0, 0)
            return popup
        }
        // prompt
        if (type == "prompt") {
            val layout = layoutInflater.inflate(R.layout.prompt, binding.fragmentParent, false)
            prompt = PopupWindow(
                layout,
                ActionBar.LayoutParams.MATCH_PARENT,
                ActionBar.LayoutParams.MATCH_PARENT,
                true
            )
            prompt.showAtLocation(layout, Gravity.CENTER, 0, 0)
            return prompt
        }
        throw Exception("Unknown popup type")
    }

    // Delete callbacks
    override fun onDialogClickYes(uri: Uri) {
        DocumentsContract.deleteDocument(this.requireActivity().contentResolver, uri)
        Utils.withDelay({ binding.toggleGroup.uncheck(R.id.action_delete) })
        currentAction = null
        observeCurrent(fragmentDocId)
    }
    override fun onDialogClickNo() {
        Utils.withDelay({ binding.toggleGroup.uncheck(R.id.action_delete) })
        currentAction = null
    }
    override fun onDismiss() {
        Utils.withDelay({ binding.toggleGroup.uncheck(R.id.action_delete) })
        currentAction = null
    }
}