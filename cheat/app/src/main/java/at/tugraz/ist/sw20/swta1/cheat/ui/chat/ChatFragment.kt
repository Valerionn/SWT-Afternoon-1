package at.tugraz.ist.sw20.swta1.cheat.ui.chat

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.tugraz.ist.sw20.swta1.cheat.ChatActivity
import at.tugraz.ist.sw20.swta1.cheat.R
import at.tugraz.ist.sw20.swta1.cheat.RecyclerItemClickListener
import at.tugraz.ist.sw20.swta1.cheat.bluetooth.BluetoothService
import at.tugraz.ist.sw20.swta1.cheat.bluetooth.BluetoothState
import kotlinx.android.synthetic.main.chat_fragment.view.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.*
import java.text.SimpleDateFormat

class ChatFragment : Fragment() {
    companion object {
        fun newInstance() = ChatFragment()
    }

    private lateinit var viewModel: ChatViewModel

    private lateinit var root: View
    private lateinit var chatAdapter: ChatHistoryAdapter
    private lateinit var recyclerView: RecyclerView
    private var currentEditMessage: ChatEntry? = null
    
    private val RESULT_SELECT_IMAGE = 1
    private val RESULT_CAPTURE_IMAGE = 2

    private lateinit var currentPhotoPath: String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        viewModel = ViewModelProvider(this).get(ChatViewModel::class.java)
        root =  inflater.inflate(R.layout.chat_fragment, container, false)

        // viewModel.insertMessage(ChatEntry("Hi", true, false, Date()))

        val header = root.item_header_text.findViewById<TextView>(R.id.title)
        header.text = BluetoothService.getConnectedDevice()?.name

        BluetoothService.setOnMessageReceive { chatEntry ->
            chatEntry.isByMe = false
            Log.i("Message", "Message received: ${chatEntry.getMessage()}")
            val scrollPosition = viewModel.insertMessage(chatEntry)
            activity!!.runOnUiThread {
                chatAdapter.notifyDataSetChanged()
                if (scrollPosition > -1) {
                    recyclerView.smoothScrollToPosition(scrollPosition)
                }
            }
        }

        BluetoothService.setOnStateChangeListener { _, newState ->
            val connection_status = root.findViewById<TextView>(R.id.connection_status)

            activity!!.runOnUiThread {
                when (newState) {
                    BluetoothState.CONNECTED -> connection_status.text =
                        getString(R.string.connected_status)
                    BluetoothState.READY -> connection_status.text =
                        getString(R.string.disconnected_status)
                    else -> {
                    }
                }
            }
        }

        chatAdapter = ChatHistoryAdapter(viewModel.getChatEntries())

        recyclerView = root.findViewById<RecyclerView>(R.id.chat_history).apply {
            layoutManager = LinearLayoutManager(context!!)
            adapter = chatAdapter
        }

        recyclerView.addOnItemTouchListener(RecyclerItemClickListener(context, recyclerView,
            object : RecyclerItemClickListener.OnItemClickListener{

            override fun onItemClick(view: View?, position: Int) {
            }

            override fun onLongItemClick(view: View?, position: Int) {
                val message = chatAdapter.getItemAt(position)
                if (message.isByMe && !message.isDeleted()) {
                    val builder = AlertDialog.Builder(activity!!)
                    builder.setTitle("Options")
                        .setItems(R.array.message_options) { _, which ->
                            if (which == 0) {
                                currentEditMessage = message
                                root.item_edit_hint.visibility = View.VISIBLE
                                root.item_edit_hint.findViewById<TextView>(R.id.tv_edit_text).text = message.getMessageShortened()
                                val etMsg = root.item_text_entry_field.findViewById<EditText>(R.id.text_entry)
                                etMsg.setText(message.getMessage())
                            } else {
                                deleteChatEntry(message)
                            }
                        }

                    builder.setNegativeButton("Cancel") { _, _ -> }

                    val dialog: AlertDialog = builder.create()
                    dialog.show()
                }
            }
        }))

        root.item_edit_hint.findViewById<Button>(R.id.btn_cancel_edit).setOnClickListener {
            currentEditMessage = null
            root.item_edit_hint.visibility = View.GONE
            val etMsg = root.item_text_entry_field.findViewById<EditText>(R.id.text_entry)
            etMsg.text.clear()
        }

        (recyclerView.layoutManager as LinearLayoutManager).stackFromEnd = true

        initSendButton()
        initPictureSendButton()
        initConnectionButton()

        return root
    }

    private fun deleteChatEntry(chatEntry: ChatEntry) {
        chatEntry.setDeleted()
        chatAdapter.notifyDataSetChanged()
        BluetoothService.sendMessage(chatEntry)

    }

    private fun initConnectionButton() {
        val connection_status = root.findViewById<TextView>(R.id.connection_status)
        connection_status.setOnClickListener {
            if(BluetoothService.state == BluetoothState.CONNECTED) {
                (activity as ChatActivity).disconnect()
            }
        }
    }

    private fun initSendButton() {
        val btnSend = root.item_text_entry_field.findViewById<Button>(R.id.btn_send)
        val etMsg = root.item_text_entry_field.findViewById<EditText>(R.id.text_entry)

        btnSend.setOnClickListener {
            val text = etMsg.text.toString().trim()
            if (BluetoothService.state != BluetoothState.CONNECTED) {
                Toast.makeText(context, "Can't sent message while disconnected.", Toast.LENGTH_SHORT).show()
            }
            else if (text.isNotBlank()) {
                var chatEntry = currentEditMessage
                if(chatEntry != null) {
                    chatEntry.edit(text)
                    currentEditMessage = null
                    root.item_edit_hint.visibility = View.GONE
                } else {
                    chatEntry = ChatEntry(text, true, false, Date())
                }
                val scrollPosition = viewModel.insertMessage(chatEntry)
                BluetoothService.sendMessage(chatEntry)
                chatAdapter.notifyDataSetChanged()
                if(scrollPosition != -1) {
                    recyclerView.smoothScrollToPosition(scrollPosition)
                }
                etMsg.text.clear()
            }
        }
    }
    
    private fun initPictureSendButton() {
        val imageBtn = root.item_text_entry_field.findViewById<ImageButton>(R.id.image_select)
    
        imageBtn.setOnClickListener {
            
            val layout = layoutInflater.inflate(R.layout.dialog_choose_image_src, null) as View
            val dialog = AlertDialog.Builder(context!!).create()
            dialog.setView(layout)
            
            layout.findViewById<Button>(R.id.dialog_image_src_camera).setOnClickListener {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

                val photoFile: File? = try {
                    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
                    val storageDir: File? = context!!.getExternalFilesDir(Environment.DIRECTORY_DCIM)

                    File.createTempFile(
                            "IMG_${timeStamp}",
                            ".jpg",
                            storageDir
                    ).apply {
                        currentPhotoPath = absolutePath
                    }
                } catch (ex: IOException) {
                    currentPhotoPath = ""
                    dialog.cancel()
                    Toast.makeText(context, "Creating image file failed.", Toast.LENGTH_SHORT).show()
                    null
                }

                photoFile?.also {
                    val photoUri = FileProvider.getUriForFile(context!!, activity!!.applicationContext.packageName + "fileprovider", it);
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    dialog.cancel()
                    startActivityForResult(intent, RESULT_CAPTURE_IMAGE)
                }
            }
    
            layout.findViewById<Button>(R.id.dialog_image_src_gallery).setOnClickListener {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                intent.type = "image/*"
                dialog.cancel()
                startActivityForResult(intent, RESULT_SELECT_IMAGE)
            }
            
            dialog.show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    
        fun sendImage (bitmap: Bitmap) {
            if (BluetoothService.state != BluetoothState.CONNECTED) {
                Toast.makeText(context, "Can't sent image while disconnected.", Toast.LENGTH_SHORT).show()
            } else {
                val builder = AlertDialog.Builder(context!!)
                builder.setTitle("Send Image")
                builder.setMessage("Do you want to send this image?")
                
                Log.d("Image", "Dim: ${bitmap.width}x${bitmap.height}")
    
                builder.setPositiveButton("YES") { dialog, which ->
                    Thread {
            
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                        val array: ByteArray = bos.toByteArray()
                        bitmap.recycle()
                        Log.d("Image", "Image compressed, size ${array.size}")
            
                        val chatEntry = ChatEntry("", array, true, false, Date())
                        val index = viewModel.insertMessage(chatEntry)
            
                        activity!!.runOnUiThread {
                            val etMsg = root.item_text_entry_field.findViewById<EditText>(R.id.text_entry)
                            etMsg.text.clear()
                            chatAdapter.notifyDataSetChanged()
                            recyclerView.smoothScrollToPosition(index)
                        }
            
                        BluetoothService.sendMessage(chatEntry)
                    }.start()
                }
    
                builder.setNegativeButton("NO"){_,_ -> }
    
                val dialog: AlertDialog = builder.create()
                dialog.show()
            }
        }
        
        if(resultCode == RESULT_OK && data != null) {
            if(requestCode == RESULT_SELECT_IMAGE) {
                Log.d("Image", "Image selected from gallery")
                sendImage(MediaStore.Images.Media.getBitmap(context?.contentResolver, data.data!!))
            } else if(requestCode == RESULT_CAPTURE_IMAGE) {
                Log.d("Image", "Image taken with camera")

                sendImage(MediaStore.Images.Media.getBitmap(context!!.getContentResolver(), Uri.parse(currentPhotoPath)))
            }
        }
    }
}