package com.bharath.scopedstorageandroid

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bharath.scopedstorageandroid.Helper.checkSdk29AndUp
import com.bharath.scopedstorageandroid.adapter.InternalStoragePhotoAdapter
import com.bharath.scopedstorageandroid.adapter.SharedPhotoAdapter
import com.bharath.scopedstorageandroid.databinding.ActivityMainBinding
import com.bharath.scopedstorageandroid.datamodel.InternalStoragePhoto
import com.bharath.scopedstorageandroid.datamodel.SharedStoragePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {


    private lateinit var binding: ActivityMainBinding
    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var contentObserver: ContentObserver

    private var hasReadPermission =false
    private var hasWritePermission =false
    private var deletedPhotoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {
            lifecycleScope.launch {
                val isDeleted = deletePhotoFromInternalStorage(it.name)
                if (isDeleted) {
                    loadPhotosFromInternalStorageIntoView()
                    Toast.makeText(this@MainActivity, "Photo Deleted Successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to delete photo!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        externalStoragePhotoAdapter = SharedPhotoAdapter {
            lifecycleScope.launch {
                deletePhotoFromExternalStorage(it.contentUri)
                deletedPhotoUri = it.contentUri
            }
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){  permissions ->
            hasReadPermission = permissions[android.Manifest.permission.READ_EXTERNAL_STORAGE] ?: hasReadPermission
            hasWritePermission = permissions[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: hasWritePermission
            if(hasReadPermission){
                loadPhotosFromExternalStorageIntoView()
            } else {
                Toast.makeText(this, "can't read files without permission ", Toast.LENGTH_SHORT).show()
            }
        }

        intentSenderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){ result ->
            if(result.resultCode == RESULT_OK){
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q){
                    lifecycleScope.launch {
                        deletePhotoFromExternalStorage(deletedPhotoUri ?: return@launch)
                    }
                }
                Toast.makeText(this, "Photo Deleted Successfully ", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "can't delete photo ", Toast.LENGTH_SHORT).show()
            }
        }

        updateRequestPermission()

        val takePicture = registerForActivityResult(ActivityResultContracts.TakePicturePreview()){

            lifecycleScope.launch {

                val isPrivate = binding.switchPrivate.isChecked

                val isSuccessFul = when {
                    isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), it)
                    hasWritePermission -> savePhotoToExternalStorage(UUID.randomUUID().toString(), it)
                    else -> false
                }

                if (isPrivate) {
                    loadPhotosFromInternalStorageIntoView()
                }

                if (isSuccessFul) {
                    Toast.makeText(this@MainActivity, "Photo Saved Successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save photo!", Toast.LENGTH_SHORT).show()
                }
            }

        }

        binding.btnTakePhoto.setOnClickListener {
            takePicture.launch()
        }

        setupInternalRecyclerView()
        setupExternalRecyclerView()
        initObserver()
        loadPhotosFromInternalStorageIntoView()
        loadPhotosFromExternalStorageIntoView()

    }


    // Check permissions are granted

    private fun updateRequestPermission(){

        val hasPermissionToRead = ContextCompat.checkSelfPermission(this , android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val hasPermissionToWrite = ContextCompat.checkSelfPermission(this , android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        val minSdk29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        hasReadPermission = hasPermissionToRead
        hasWritePermission = hasPermissionToWrite || minSdk29

        val permissionsToRequest = mutableListOf<String>()

        if(!hasReadPermission){
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if(!hasWritePermission){
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if(permissionsToRequest.isNotEmpty()){
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

    }


    private fun initObserver(){
        contentObserver = object : ContentObserver(null){
            override fun onChange(selfChange: Boolean) {
                if(hasReadPermission){
                    loadPhotosFromExternalStorageIntoView()
                }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
    }


    // Binding data to view

    private fun setupInternalRecyclerView() = binding.rvPrivatePhotos.apply{
        adapter = internalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager( 3 , RecyclerView.VERTICAL)
    }

    private fun setupExternalRecyclerView() = binding.rvPublicPhotos.apply{
        adapter = externalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager( 3 , RecyclerView.VERTICAL)
    }

    private fun loadPhotosFromInternalStorageIntoView(){
        lifecycleScope.launch {
            val photoList = loadPhotoFromInternalStorage()
            internalStoragePhotoAdapter.submitList(photoList)
        }
    }

    private fun loadPhotosFromExternalStorageIntoView(){
        lifecycleScope.launch {
            val photoList = loadPhotoFromExternalStorage()
            externalStoragePhotoAdapter.submitList(photoList)
        }
    }



    // Start Region External Storage Methods

    private suspend fun savePhotoToExternalStorage(filename: String , bmp: Bitmap): Boolean{

        return withContext(Dispatchers.IO) {

            val imageCollection = checkSdk29AndUp {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, bmp.width)
                put(MediaStore.Images.Media.HEIGHT, bmp.height)
            }

            try {
                contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                    contentResolver.openOutputStream(uri).use { outputStream ->
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)) {
                            throw IOException("couldn't Save Image")
                        }
                    }
                } ?: throw IOException("couldn't create mediastore entry")
                true
            } catch (exception: IOException) {
                exception.printStackTrace()
                false
            }
        }

    }

    private suspend fun deletePhotoFromExternalStorage(photoUri: Uri){
        withContext(Dispatchers.IO){
            try {
                contentResolver.delete(photoUri ,null ,null)
            }catch (ex: SecurityException){
                val intentSender = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->  {
                        MediaStore.createDeleteRequest(contentResolver , listOf(photoUri)).intentSender
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->  {
                        val recoverableSecurityException = ex as? RecoverableSecurityException
                        recoverableSecurityException?.userAction?.actionIntent?.intentSender
                    }
                    else -> null
                }
                intentSender?.let {  sender ->
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(sender).build()
                    )
                }
            }
        }
    }

    private suspend fun loadPhotoFromExternalStorage(): List<SharedStoragePhoto>{
        return withContext(Dispatchers.IO) {

            val photoList = mutableListOf<SharedStoragePhoto>()

            val collectionOfImages = checkSdk29AndUp {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )

            contentResolver.query(
                collectionOfImages,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val columnId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val columnName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val columnWidth = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val columnHeight = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {

                    val id = cursor.getLong(columnId)
                    val name = cursor.getString(columnName)
                    val width = cursor.getInt(columnWidth)
                    val height = cursor.getInt(columnHeight)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    photoList.add(SharedStoragePhoto(id, name, width, height, contentUri))
                }

                photoList.toList()

            } ?: listOf()
        }
    }

    // End Region External Storage Methods

    // Start Region Inernal Storage Methods

    private suspend fun savePhotoToInternalStorage(filename: String , bmp: Bitmap): Boolean{
        return withContext(Dispatchers.IO) {
            try {
                openFileOutput("$filename.jpg", MODE_PRIVATE).use { stream ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                        throw IOException("file save unsuccessful")
                    }
                }
                true
            } catch (exception: IOException) {
                exception.printStackTrace()
                false
            }
        }
    }

    private suspend fun deletePhotoFromInternalStorage(filename: String): Boolean{
        return withContext(Dispatchers.IO) {
            try {
                deleteFile(filename)
            } catch (exception: Exception) {
                exception.printStackTrace()
                false
            }
        }
    }

    private suspend fun loadPhotoFromInternalStorage(): List<InternalStoragePhoto>{
        return withContext(Dispatchers.IO){
              val files = filesDir.listFiles()
            files?.filter { it.canRead() && it.isFile && it.name.endsWith(".jpg") }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes , 0 , bytes.size)
                InternalStoragePhoto(it.name , bmp)
            } ?: listOf()
        }
    }

    // End Region Internal Sorage methods

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
    }

}