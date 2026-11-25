package cn.tonglu.app.data.books

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

interface BooksApi {
    @GET("books/isbn/{isbn}")
    suspend fun bookIsbn(@Header("Authorization") bearer: String, @Path("isbn") isbn: String): BookMetadata

    @GET("books/listings")
    suspend fun bookListings(@Header("Authorization") bearer: String, @QueryMap filters: Map<String, String>): BookPage

    @GET("books/listings/for-my-courses")
    suspend fun booksForMyCourses(@Header("Authorization") bearer: String, @QueryMap filters: Map<String, String>): BookPage

    @GET("books/listings/{id}")
    suspend fun bookDetail(@Header("Authorization") bearer: String, @Path("id") id: String): BookListing

    @POST("books/listings")
    suspend fun createBook(@Header("Authorization") bearer: String, @Body input: CreateBookListing): BookListing

    @PATCH("books/listings/{id}")
    suspend fun updateBook(@Header("Authorization") bearer: String, @Path("id") id: String, @Body input: UpdateBookListing)

    @POST("books/listings/{id}/off")
    suspend fun takeBookOff(@Header("Authorization") bearer: String, @Path("id") id: String)

    @POST("books/listings/{id}/report")
    suspend fun reportBook(@Header("Authorization") bearer: String, @Path("id") id: String, @Body input: ReportBookListing)

    @GET("books/listings/{id}/requests")
    suspend fun bookRequests(@Header("Authorization") bearer: String, @Path("id") id: String): List<BookRequest>

    @POST("books/listings/{id}/requests")
    suspend fun createBookRequest(@Header("Authorization") bearer: String, @Path("id") id: String, @Body input: CreateBookRequest)

    @POST("books/requests/{id}/accept")
    suspend fun acceptBookRequest(@Header("Authorization") bearer: String, @Path("id") id: String)

    @POST("books/requests/{id}/decline")
    suspend fun declineBookRequest(@Header("Authorization") bearer: String, @Path("id") id: String)

    @POST("books/requests/{id}/cancel")
    suspend fun cancelBookRequest(@Header("Authorization") bearer: String, @Path("id") id: String)

    @POST("books/listings/{id}/sold")
    suspend fun markBookSold(@Header("Authorization") bearer: String, @Path("id") id: String)

    @POST("books/listings/{id}/reviews")
    suspend fun reviewBook(@Header("Authorization") bearer: String, @Path("id") id: String, @Body input: CreateBookReview)

    @GET("books/users/{id}/stats")
    suspend fun bookStats(@Header("Authorization") bearer: String, @Path("id") id: String): BookStats

    @GET("books/me/listings")
    suspend fun myBookListings(@Header("Authorization") bearer: String): List<BookListing>

    @GET("books/me/requests")
    suspend fun myBookRequests(@Header("Authorization") bearer: String): List<BookRequest>

    @GET("books/wanted")
    suspend fun myBookWanted(@Header("Authorization") bearer: String): List<BookWanted>

    @POST("books/wanted")
    suspend fun createBookWanted(@Header("Authorization") bearer: String, @Body input: CreateBookWanted)

    @DELETE("books/wanted/{id}")
    suspend fun deleteBookWanted(@Header("Authorization") bearer: String, @Path("id") id: String)

    @GET("media/{id}")
    suspend fun bookPhoto(@Header("Authorization") bearer: String, @Path("id") id: String): BookPhoto

    @POST("media/uploads")
    suspend fun beginBookPhoto(@Header("Authorization") bearer: String, @Body input: BeginBookPhoto): BookPhotoTicket

    @POST("media/uploads/{id}/complete")
    suspend fun completeBookPhoto(@Header("Authorization") bearer: String, @Path("id") id: String, @Body dimensions: Map<String, Int>): BookPhoto
}
